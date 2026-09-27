package adserve.core.caps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCapStoreTest {
    @Test
    void idempotentCountsOncePerEventNaiveCountsEveryDelivery() {
        InMemoryCapStore good = new InMemoryCapStore(true);
        InMemoryCapStore naive = new InMemoryCapStore(false);
        String ev = EventIds.impression("imp-1");
        for (int i = 0; i < 3; i++) {
            good.recordImpression("v", "c", ev, 1000);
            naive.recordImpression("v", "c", ev, 1000);
        }
        assertThat(good.fetch("v", List.of("c"), 1000).day()[0]).isEqualTo(1);
        assertThat(naive.fetch("v", List.of("c"), 1000).day()[0]).isEqualTo(3);
    }

    @Test
    void eventIdIsStableAndDistinguishesTypeAndOffset() {
        assertThat(EventIds.impression("x")).isEqualTo(EventIds.impression("x"));
        assertThat(EventIds.of("x", ads.v1.EventType.START, 0)).isNotEqualTo(EventIds.impression("x"));
        assertThat(EventIds.of("x", ads.v1.EventType.IMPRESSION, 1)).isNotEqualTo(EventIds.impression("x"));
    }
}
