package adserve.beacons;

import adserve.core.caps.CapStoreUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A serving node must start, and fall through to its cap mode, while Redis is down. */
class RedisDownTest {
    @Test
    void startsWithoutRedisAndReportsUnavailable() {
        try (RedisCounters rc = new RedisCounters("redis://127.0.0.1:1", 20, true, 2)) {
            assertThatThrownBy(() -> rc.fetch("v", List.of("c"), 0)).isInstanceOf(CapStoreUnavailableException.class);
            rc.recordPod("v", new String[]{"c"}, new String[]{"e"}, 0); // counted as a write error, never thrown
        }
    }
}
