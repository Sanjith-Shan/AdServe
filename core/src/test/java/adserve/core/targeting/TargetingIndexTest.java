package adserve.core.targeting;

import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Device;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TargetingIndexTest {
    static Campaign campaign(String id, TargetingSpec t) {
        return new Campaign(id, "a-" + id, id, "retail", 1_000_000, 10_000_000, 0, Long.MAX_VALUE,
                PacerKind.UNPACED, FrequencyCap.NONE, t, List.of(new CreativeSpec(id + "-cr", 30, 0.01)), true);
    }

    @Test
    void evaluatesEveryDimension() {
        TargetingSpec t = new TargetingSpec(Set.of("r1", "r2"), Set.of(Device.TV), Set.of("s1", "s9"), Set.of("kids"));
        TargetingIndex idx = new TargetingIndex(List.of(campaign("c", t), campaign("any", TargetingSpec.ANY)));
        assertThat(idx.matches(0, idx.context("r1", "tv", List.of("s0", "s9"), "drama"))).isTrue();
        assertThat(idx.matches(0, idx.context("r3", "tv", List.of("s9"), "drama"))).as("geo").isFalse();
        assertThat(idx.matches(0, idx.context("r1", "web", List.of("s9"), "drama"))).as("device").isFalse();
        assertThat(idx.matches(0, idx.context("r1", "tv", List.of("s0"), "drama"))).as("segment").isFalse();
        assertThat(idx.matches(0, idx.context("r1", "tv", List.of("s1"), "kids"))).as("genre").isFalse();
        assertThat(idx.matches(1, idx.context(null, null, List.of(), null))).as("untargeted").isTrue();
    }

    @Test
    void handlesMoreThan64Segments() {
        java.util.Set<String> many = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) many.add("s" + i);
        TargetingIndex idx = new TargetingIndex(List.of(campaign("c", new TargetingSpec(Set.of(), Set.of(), many, Set.of())),
                campaign("d", new TargetingSpec(Set.of(), Set.of(), Set.of("s199"), Set.of()))));
        assertThat(idx.matches(1, idx.context("r", "tv", List.of("s199"), "g"))).isTrue();
        assertThat(idx.matches(1, idx.context("r", "tv", List.of("s198"), "g"))).isFalse();
    }
}
