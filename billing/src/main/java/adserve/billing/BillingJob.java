package adserve.billing;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.AbstractDeserializationSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.core.execution.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.time.Duration;
import java.util.List;

/**
 * The billing job: Normalize, Join, Deduplicate, Publish, over the decision log and the beacon
 * topics. Its output is billing_events, one row per billable impression, counted once however
 * many times the device retried.
 *
 * <p>Environment: KAFKA_BOOTSTRAP, JDBC_URL, JDBC_USER, JDBC_PASSWORD, ADS_TOKEN_KEY, PARALLELISM, and
 * BILLING_START ("committed", the default, resumes the consumer group; "latest" skips history).
 * Run it standalone ({@code ./gradlew :billing:run}, an embedded mini-cluster) or submit the jar
 * to a Flink cluster.
 */
public final class BillingJob {
    public static final Duration JOIN_WINDOW = Duration.ofMinutes(60);
    public static final Duration DEDUP_TTL = Duration.ofHours(24);

    public static void main(String[] args) throws Exception {
        String kafka = env("KAFKA_BOOTSTRAP", "localhost:29092");
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(5_000, CheckpointingMode.AT_LEAST_ONCE);
        env.setParallelism(Integer.parseInt(env("PARALLELISM", "2")));

        DataStream<byte[]> decisions = env.fromSource(source(kafka, List.of("ad.responses"), "billing-decisions"),
                WatermarkStrategy.noWatermarks(), "ad.responses");
        DataStream<byte[]> beacons = env.fromSource(source(kafka, List.of("ad.beacons.us_east", "ad.beacons.us_west"),
                "billing-beacons"), WatermarkStrategy.noWatermarks(), "ad.beacons");

        build(decisions.flatMap(new Normalize.Decisions()).name("normalize decisions"),
                beacons.flatMap(new Normalize.Beacons()).name("normalize beacons"))
                .sinkTo(new BillingTableSink(env("JDBC_URL", "jdbc:postgresql://localhost:25432/adserve"),
                        env("JDBC_USER", "adserve"), env("JDBC_PASSWORD", "adserve"), "flink"))
                .name("billing_events");
        env.execute("adserve-billing");
    }

    /** The topology between the normalized inputs and the table, shared with the tests. */
    public static DataStream<BillingRow> build(DataStream<ImpressionContext> contexts, DataStream<BeaconEvent> beacons) {
        return contexts.keyBy(c -> c.impressionId)
                .connect(beacons.keyBy(b -> b.impressionId))
                .process(new JoinBeacons(JOIN_WINDOW)).name("join")
                .keyBy(r -> r.eventId)
                .process(new Deduplicate(DEDUP_TTL)).name("deduplicate");
    }

    static KafkaSource<byte[]> source(String bootstrap, List<String> topics, String group) {
        return KafkaSource.<byte[]>builder()
                .setBootstrapServers(bootstrap)
                .setTopics(topics)
                .setGroupId(group)
                .setStartingOffsets("latest".equals(env("BILLING_START", "committed"))
                        ? OffsetsInitializer.latest()
                        : OffsetsInitializer.committedOffsets(org.apache.kafka.clients.consumer.OffsetResetStrategy.EARLIEST))
                .setValueOnlyDeserializer(new AbstractDeserializationSchema<>() {
                    @Override
                    public byte[] deserialize(byte[] message) {
                        return message;
                    }
                })
                .build();
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
