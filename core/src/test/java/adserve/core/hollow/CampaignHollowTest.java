package adserve.core.hollow;

import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Device;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignHollowTest {
    static Campaign c(int i, long budget) {
        return new Campaign("c" + i, "a" + (i % 5), "n" + i, i % 2 == 0 ? "auto" : "retail", 1000 + i, budget, 0, 10_000,
                PacerKind.SMART, new FrequencyCap(3, 9),
                new TargetingSpec(Set.of("r0", "r" + (i + 1)), Set.of(Device.TV), Set.of("t1"), Set.of("kids")),
                List.of(new CreativeSpec("cr" + i, 30, 0.001 * i), new CreativeSpec("cr" + i + "b", 15, 0.002)), true);
    }

    @Test
    void snapshotThenDeltaReachTheConsumer(@TempDir Path dir) {
        List<Campaign> all = new ArrayList<>();
        for (int i = 0; i < 55; i++) all.add(c(i, 1_000_000L * (i + 1)));
        CampaignHollow.Publisher pub = new CampaignHollow.Publisher(dir);
        long v1 = pub.publish(all);
        CampaignHollow.Source src = new CampaignHollow.Source(dir, false);
        src.refreshTo(v1);
        assertThat(src.current().campaigns()).containsExactlyInAnyOrderElementsOf(all);
        assertThat(src.snapshotsApplied.get()).isEqualTo(1);

        all.set(7, c(7, 42));
        long v2 = pub.publish(all);
        src.refreshTo(v2);
        assertThat(src.deltasApplied.get()).isEqualTo(1);
        assertThat(src.current().campaign(src.current().indexOf("c7")).dailyBudgetMicros()).isEqualTo(42);
        assertThat(src.current().version()).isEqualTo(v2);
    }
}
