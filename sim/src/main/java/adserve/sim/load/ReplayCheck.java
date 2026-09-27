package adserve.sim.load;

import ads.v1.AdRequest;
import ads.v1.DecisionRecord;
import adserve.core.io.RequestFiles;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Milestone 1's check: replay N real-log requests through the gRPC endpoint, then read ad.responses
 * back from Kafka and confirm every decision was logged.
 * Args: requestsFile n ratePerSecond grpcTarget kafkaBootstrap
 */
public final class ReplayCheck {
    public static void main(String[] a) throws Exception {
        Path file = Path.of(a.length > 0 ? a[0] : "data/work/requests-20130611.bin");
        int n = a.length > 1 ? Integer.parseInt(a[1]) : 10_000;
        double rate = a.length > 2 ? Double.parseDouble(a[2]) : 2_000;
        String target = a.length > 3 ? a[3] : "localhost:29100";
        String kafka = a.length > 4 ? a[4] : "localhost:29092";

        List<AdRequest> reqs = RequestFiles.readAll(file, n);
        String run = UUID.randomUUID().toString().substring(0, 8);
        Set<String> ids = new HashSet<>();
        AdRequest[] stamped = new AdRequest[reqs.size()];
        for (int i = 0; i < stamped.length; i++) {
            stamped[i] = reqs.get(i).toBuilder().setRequestId(run + "-" + reqs.get(i).getRequestId()).build();
            ids.add(stamped[i].getRequestId());
        }
        LoadGen.Tally live = new LoadGen.Tally(), vod = new LoadGen.Tally();
        long t0 = System.nanoTime();
        try (LoadGen gen = new LoadGen(target, 4, 2000)) {
            gen.run(stamped.length, LoadGen.constantRate(rate), i -> stamped[i], live, vod);
        }
        double secs = (System.nanoTime() - t0) / 1e9;

        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "replay-check-" + run);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);
        Set<String> seen = new HashSet<>();
        long withPod = 0, impressions = 0;
        try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
            c.subscribe(List.of("ad.responses"));
            long deadline = System.currentTimeMillis() + 60_000;
            while (seen.size() < ids.size() && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                    if (!ids.contains(r.key()) || !seen.add(r.key())) continue;
                    DecisionRecord d = DecisionRecord.parseFrom(r.value());
                    if (d.getResponse().getPodCount() > 0) withPod++;
                    impressions += d.getResponse().getPodCount();
                }
            }
        }
        ObjectNode out = Results.line("m1_replay_check");
        out.put("requests_file", file.toString());
        out.put("traffic", "iPinYou season 2, 2013-06-11 impression log, first " + n + " rows as ad breaks; simulated viewers");
        out.put("offered_per_s", rate);
        out.put("load_generator", "same machine, separate JVM, open loop");
        LoadGen.summarize(out, "vod", vod, secs);
        out.put("unique_request_ids", ids.size());
        out.put("decision_records_on_kafka", seen.size());
        out.put("decisions_with_ads", withPod);
        out.put("impressions_logged", impressions);
        Results.append("m1_replay.jsonl", out);
        System.out.println(out.toPrettyString());
        if (seen.size() != ids.size()) System.exit(1);
    }
}
