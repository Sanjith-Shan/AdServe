package adserve.beacons;

import adserve.core.caps.EventIds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The Lua scripts against a real Redis: one count per impression however it is written. */
@Tag("integration")
@Testcontainers
class RedisCountersIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static final long TS = 1_370_950_000_000L;

    String uri() {
        return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
    }

    @Test
    void decisionTimePodWriteAndRetriedBeaconsCountOnce() throws Exception {
        try (RedisCounters rc = new RedisCounters(uri(), 1000, true, 2)) {
            String a = EventIds.impression("imp-a"), b = EventIds.impression("imp-b");
            rc.recordPod("v1", new String[]{"c1", "c2"}, new String[]{a, b}, TS);
            // Concurrent retried beacons for the same two impressions.
            ExecutorService ex = Executors.newFixedThreadPool(8);
            List<CompletableFuture<Void>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                fs.add(CompletableFuture.runAsync(() -> {
                    try {
                        rc.recordImpressionAsync("v1", "c1", a, TS).get(5, TimeUnit.SECONDS);
                        rc.recordImpressionAsync("v1", "c2", b, TS).get(5, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, ex));
            }
            CompletableFuture.allOf(fs.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            ex.shutdown();
            var counts = rc.fetch("v1", List.of("c1", "c2"), TS);
            assertThat(counts.day()).containsExactly(1, 1);
            assertThat(counts.week()).containsExactly(1, 1);
            assertThat(counts.viewerHour()).isEqualTo(2);
        }
    }

    @Test
    void naiveCountsEveryDelivery() throws Exception {
        try (RedisCounters rc = new RedisCounters(uri(), 1000, false)) {
            String e = EventIds.impression("imp-n");
            for (int i = 0; i < 3; i++) rc.recordImpressionAsync("v2", "c1", e, TS).get(5, TimeUnit.SECONDS);
            assertThat(rc.fetch("v2", List.of("c1"), TS).day()).containsExactly(3);
        }
    }

    @Test
    void spendCountsOncePerEvent() throws Exception {
        try (RedisCounters rc = new RedisCounters(uri(), 1000, true)) {
            String e = EventIds.impression("imp-s");
            for (int i = 0; i < 4; i++) rc.recordSpendAsync("c9", e, 1234, TS).get(5, TimeUnit.SECONDS);
            assertThat(rc.spend(List.of("c9"), adserve.core.caps.Windows.day(TS))).containsExactly(1234);
        }
    }
}
