package adserve.sim.billing;

import ads.v1.AdDecisionGrpc;
import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Beacon;
import ads.v1.EventType;
import ads.v1.Impression;
import ads.v1.Priority;
import ads.v1.Region;
import adserve.core.io.RequestFiles;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Simulated players: each asks AdServe for a break over gRPC, then fires the VAST beacons for
 * every impression in the pod to ad.beacons.<region>, the way a device would. Nobody watches
 * anything; completion and click behaviour are drawn from fixed rates. Beacons are duplicated
 * (retries with the same beacon_id), lost, and sent to the wrong region at configurable rates, and
 * the run returns the ground truth the billing audit checks against.
 */
public final class PlayerSim {
    public record Truth(Set<String> servedImpressions, Set<String> beaconedImpressions, long beaconsSent,
                        long duplicatesSent, long misrouted, long lost) {}

    public static Truth play(String grpcTarget, String kafka, Path requests, int breaks, String runId,
                             double dupRate, double lossRate, double misrouteRate, long seed) throws Exception {
        List<AdRequest> reqs = RequestFiles.readAll(requests, breaks);
        SplittableRandom rnd = new SplittableRandom(seed);
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        Set<String> served = new HashSet<>(), beaconed = new HashSet<>();
        long sent = 0, dups = 0, misrouted = 0, lost = 0;
        ManagedChannel ch = NettyChannelBuilder.forTarget(grpcTarget).usePlaintext().build();
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer())) {
            AdDecisionGrpc.AdDecisionBlockingStub stub = AdDecisionGrpc.newBlockingStub(ch);
            for (int n = 0; n < reqs.size(); n++) {
                AdRequest r = reqs.get(n).toBuilder().setRequestId(runId + "-" + n)
                        .setViewerId(runId + "-" + reqs.get(n).getViewerId()).setPriority(Priority.VOD).build();
                AdResponse resp = stub.withDeadlineAfter(5, TimeUnit.SECONDS).decide(r);
                for (Impression imp : resp.getPodList()) {
                    served.add(imp.getImpressionId());
                    int duration = imp.getCreative().getDurationS();
                    boolean completes = rnd.nextDouble() < 0.85;
                    int[] offsets = {0, 0, duration / 4, duration / 2, 3 * duration / 4, duration};
                    EventType[] types = {EventType.IMPRESSION, EventType.START, EventType.FIRST_QUARTILE,
                            EventType.MIDPOINT, EventType.THIRD_QUARTILE, EventType.COMPLETE};
                    for (int k = 0; k < types.length; k++) {
                        if (k >= 3 && !completes) break;
                        if (rnd.nextDouble() < lossRate) {
                            lost++;
                            continue;
                        }
                        Region arrival = r.getRegion();
                        if (rnd.nextDouble() < misrouteRate) {
                            arrival = arrival == Region.US_EAST ? Region.US_WEST : Region.US_EAST;
                            misrouted++;
                        }
                        Beacon b = Beacon.newBuilder().setToken(imp.getToken()).setType(types[k])
                                .setClientTsMs(r.getTsMs() + offsets[k] * 1000L).setDevice(r.getDevice())
                                .setArrivalRegion(arrival).setOffsetS(offsets[k])
                                .setBeaconId(imp.getImpressionId() + "-" + k).setSchema("vod").build();
                        int copies = 1;
                        while (copies < 4 && rnd.nextDouble() < dupRate) copies++;
                        for (int c = 0; c < copies; c++) {
                            producer.send(new ProducerRecord<>("ad.beacons." + arrival.name().toLowerCase(), imp.getToken(), b.toByteArray()));
                            sent++;
                        }
                        dups += copies - 1;
                        if (types[k] == EventType.IMPRESSION) beaconed.add(imp.getImpressionId());
                    }
                }
            }
            producer.flush();
        } finally {
            ch.shutdownNow();
        }
        return new Truth(served, beaconed, sent, dups, misrouted, lost);
    }
}
