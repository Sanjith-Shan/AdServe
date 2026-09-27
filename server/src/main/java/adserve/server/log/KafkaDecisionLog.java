package adserve.server.log;

import ads.v1.DecisionRecord;
import adserve.core.engine.DecisionLog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * "Log and move on." The request thread offers the record to a bounded in-memory queue and
 * returns; one publisher thread drains the queue into a Kafka producer with acks=1. Nothing on
 * the decision path waits for Kafka. When Kafka is slow or down the queue fills and further
 * records are dropped and counted, which is the trade the design makes: the decision log is
 * best-effort, the response is not.
 */
public final class KafkaDecisionLog implements DecisionLog, AutoCloseable {
    private final BlockingQueue<DecisionRecord> queue;
    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final Thread publisher;
    private final Counter dropped;
    private final Counter sendErrors;
    private final Counter sent;
    private volatile boolean running = true;

    public KafkaDecisionLog(String bootstrap, String topic, int capacity, MeterRegistry registry) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, 256 * 1024);
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 64L * 1024 * 1024);
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000);
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "adserve-decision-log");
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
        this.topic = topic;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.dropped = Counter.builder("adserve.decision.log.dropped").register(registry);
        this.sendErrors = Counter.builder("adserve.decision.log.send.errors").register(registry);
        this.sent = Counter.builder("adserve.decision.log.sent").register(registry);
        Gauge.builder("adserve.decision.log.queue", queue, BlockingQueue::size).register(registry);
        this.publisher = Thread.ofPlatform().name("decision-log-publisher").daemon().start(this::drain);
    }

    @Override
    public void publish(DecisionRecord record) {
        if (!queue.offer(record)) dropped.increment();
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            DecisionRecord r;
            try {
                r = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (r == null) continue;
            try {
                producer.send(new ProducerRecord<>(topic, r.getResponse().getRequestId(), r.toByteArray()),
                        (md, e) -> {
                            if (e != null) sendErrors.increment();
                            else sent.increment();
                        });
            } catch (RuntimeException e) {
                sendErrors.increment();
            }
        }
    }

    public long dropped() {
        return (long) dropped.count();
    }

    @Override
    public void close() {
        running = false;
        try {
            publisher.join(5000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        producer.flush();
        producer.close();
    }
}
