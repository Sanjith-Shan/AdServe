package adserve.billing;

import adserve.core.caps.EventIds;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The join and dedup stages on an embedded Flink: beacons that arrive before or after their
 * decision, three copies of one impression's beacon, and one beacon in the wrong region.
 */
class BillingTopologyTest {

    static BeaconEvent beacon(String imp, String arrival) {
        return new BeaconEvent(EventIds.impression(imp), imp, 1, 0, 1000, arrival, "US_EAST");
    }

    @Test
    void joinsDedupesAndFlagsReroutes() throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(2);
        List<ImpressionContext> ctx = List.of(
                new ImpressionContext("i1", "c1", "cr1", "v1", 100, 10, "US_EAST"),
                new ImpressionContext("i2", "c2", "cr2", "v1", 200, 10, "US_EAST"),
                new ImpressionContext("i3", "c1", "cr1", "v2", 300, 10, "US_EAST"));
        List<BeaconEvent> beacons = new ArrayList<>();
        for (int k = 0; k < 3; k++) beacons.add(beacon("i1", "US_EAST")); // retried twice
        beacons.add(beacon("i2", "US_WEST"));                               // arrived in the wrong region
        beacons.add(beacon("i4", "US_EAST"));                               // no decision: never billed
        DataStream<BillingRow> out = BillingJob.build(env.fromData(ctx), env.fromData(beacons));
        List<BillingRow> rows = new ArrayList<>();
        out.executeAndCollect().forEachRemaining(rows::add);

        assertThat(rows).extracting(r -> r.impressionId).containsExactlyInAnyOrder("i1", "i2");
        assertThat(rows).filteredOn(r -> r.impressionId.equals("i2")).allSatisfy(r -> {
            assertThat(r.rerouted).isTrue();
            assertThat(r.priceMicros).isEqualTo(200);
        });
        assertThat(rows).filteredOn(r -> r.impressionId.equals("i1")).allSatisfy(r -> assertThat(r.rerouted).isFalse());
    }
}
