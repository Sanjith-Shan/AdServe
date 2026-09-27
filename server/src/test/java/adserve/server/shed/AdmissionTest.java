package adserve.server.shed;

import ads.v1.Priority;
import adserve.server.config.AdServeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionTest {
    @Test
    void vodIsRefusedFirstAndLiveKeepsTheReserve() {
        // Capacity 1000/s, bucket of 100 tokens, 30 kept for LIVE.
        Admission a = new Admission(new AdServeProperties.Shedding(true, 1000, 0.1, 0.3, 10_000, 1500), new SimpleMeterRegistry());
        int vod = 0;
        while (a.tryAcquire(Priority.VOD) == Admission.Outcome.ADMIT) {
            a.release();
            vod++;
        }
        // VOD stops at the reserve, give or take refill during the loop.
        assertThat(vod).isBetween(69, 75);
        int live = 0;
        while (a.tryAcquire(Priority.LIVE) == Admission.Outcome.ADMIT) {
            a.release();
            live++;
        }
        assertThat(live).isBetween(29, 35);
        assertThat(a.retryAfterMs()).isEqualTo(1500);
    }

    @Test
    void inFlightBoundRefusesVodBeforeLive() {
        Admission a = new Admission(new AdServeProperties.Shedding(true, 1e9, 1, 0.5, 10, 1000), new SimpleMeterRegistry());
        for (int i = 0; i < 5; i++) assertThat(a.tryAcquire(Priority.VOD)).isEqualTo(Admission.Outcome.ADMIT);
        assertThat(a.tryAcquire(Priority.VOD)).isEqualTo(Admission.Outcome.SHED);
        for (int i = 0; i < 5; i++) assertThat(a.tryAcquire(Priority.LIVE)).isEqualTo(Admission.Outcome.ADMIT);
        assertThat(a.tryAcquire(Priority.LIVE)).isEqualTo(Admission.Outcome.SHED);
    }

    @Test
    void disabledAdmitsEverything() {
        Admission a = new Admission(new AdServeProperties.Shedding(false, 1, 1, 0.3, 1, 1000), new SimpleMeterRegistry());
        for (int i = 0; i < 1000; i++) assertThat(a.tryAcquire(Priority.VOD)).isEqualTo(Admission.Outcome.ADMIT);
    }
}
