package adserve.beacons;

import ads.v1.Beacon;
import adserve.core.token.TokenCodec;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.lettuce.core.RedisFuture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * The beacon consumer: reads ad.beacons.us_east and ad.beacons.us_west, and for every IMPRESSION
 * beacon counts the impression against the viewer's frequency caps and the campaign's confirmed
 * spend. At-least-once: offsets are committed only after the batch's Redis writes complete, and
 * the idempotent scripts make the redelivery that at-least-once implies harmless.
 *
 * <p>Environment: KAFKA_BOOTSTRAP (localhost:29092), REDIS_URI (redis://localhost:26379),
 * ADS_TOKEN_KEY, METRICS_PORT (28081), NAIVE_COUNTERS (false).
 */
public final class BeaconConsumer {
    private static final Logger log = LoggerFactory.getLogger(BeaconConsumer.class);
    public static final List<String> TOPICS = List.of("ad.beacons.us_east", "ad.beacons.us_west");

    public static void main(String[] args) throws Exception {
        String kafka = env("KAFKA_BOOTSTRAP", "localhost:29092");
        String redis = env("REDIS_URI", "redis://localhost:26379");
        int metricsPort = Integer.parseInt(env("METRICS_PORT", "28081"));
        boolean naive = Boolean.parseBoolean(env("NAIVE_COUNTERS", "false"));

        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        HttpServer http = HttpServer.create(new InetSocketAddress(metricsPort), 0);
        http.createContext("/metrics", ex -> {
            byte[] body = registry.scrape().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(body);
            }
        });
        http.start();

        try (RedisCounters counters = new RedisCounters(redis, 2000, !naive)) {
            BeaconProcessor proc = new BeaconProcessor(TokenCodec.fromEnv(), counters, null);
            FunctionCounter.builder("beacons.processed", proc.processed, a -> a.sum()).register(registry);
            FunctionCounter.builder("beacons.impressions", proc.impressions, a -> a.sum()).register(registry);
            FunctionCounter.builder("beacons.bad_tokens", proc.badTokens, a -> a.sum()).register(registry);
            run(kafka, proc, "adserve-beacons", () -> false);
        }
    }

    /** Consumes until {@code stop} says so. Exposed for the end-to-end test. */
    public static void run(String kafka, BeaconProcessor proc, String group, java.util.function.BooleanSupplier stop) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2000);
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(TOPICS);
            log.info("consuming {}", TOPICS);
            while (!stop.getAsBoolean()) {
                ConsumerRecords<String, byte[]> batch = c.poll(Duration.ofMillis(200));
                if (batch.isEmpty()) continue;
                List<RedisFuture<?>> pending = new ArrayList<>();
                for (ConsumerRecord<String, byte[]> r : batch) {
                    try {
                        pending.addAll(proc.process(Beacon.parseFrom(r.value())));
                    } catch (Exception e) {
                        proc.badTokens.increment();
                    }
                }
                for (RedisFuture<?> f : pending) {
                    try {
                        f.get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // Leave the offset uncommitted: the batch is redelivered, and the scripts dedupe it.
                        throw new IllegalStateException("counter write failed; not committing", e);
                    }
                }
                c.commitSync();
            }
        }
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
