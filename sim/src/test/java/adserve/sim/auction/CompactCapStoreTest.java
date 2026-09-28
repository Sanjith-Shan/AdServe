package adserve.sim.auction;

import adserve.core.caps.CapCounts;
import adserve.core.caps.InMemoryCapStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

class CompactCapStoreTest {

    /** Random impressions over viewers, campaigns, hours and weeks: every read matches the reference store. */
    @Test
    void readsMatchTheInMemoryStore() {
        AuctionSim.CompactCapStore compact = new AuctionSim.CompactCapStore();
        InMemoryCapStore reference = new InMemoryCapStore(true);
        SplittableRandom r = new SplittableRandom(7);
        List<String> campaigns = List.of("c0", "c1", "c2", "c3", "c4", "c5");
        long t0 = 1_370_908_800_000L; // 2013-06-11 00:00 UTC
        long ts = t0;
        for (int i = 0; i < 20_000; i++) {
            ts += r.nextLong(0, 300_000); // up to 5 minutes; crosses hours, days and weeks
            String viewer = "v" + r.nextInt(300);
            CapCounts a = compact.fetch(viewer, campaigns, ts);
            CapCounts b = reference.fetch(viewer, campaigns, ts);
            assertThat(a.day()).containsExactly(b.day());
            assertThat(a.week()).containsExactly(b.week());
            assertThat(a.viewerHour()).isEqualTo(b.viewerHour());
            assertThat(a.known()).isTrue();
            int k = r.nextInt(4);
            for (int j = 0; j < k; j++) {
                String c = campaigns.get(r.nextInt(campaigns.size()));
                String event = "e" + i + "-" + j;
                compact.recordImpression(viewer, c, event, ts);
                reference.recordImpression(viewer, c, event, ts);
            }
        }
        assertThat(ts - t0).isGreaterThan(14L * 86_400_000L);
    }
}
