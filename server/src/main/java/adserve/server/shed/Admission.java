package adserve.server.shed;

import ads.v1.Priority;
import adserve.server.config.AdServeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Priority load shedding. One token bucket refills at the measured capacity; a LIVE request may
 * take any token, a VOD request only while the bucket holds more than the LIVE reserve. On top,
 * an in-flight bound refuses VOD first as concurrency climbs. A refused request gets
 * RESOURCE_EXHAUSTED (HTTP 503) with a retry hint, so during a synchronized live break the
 * people watching live keep getting ads while on-demand requests back off and retry.
 */
public final class Admission {
    public enum Outcome { ADMIT, SHED }

    private final boolean enabled;
    private final double ratePerNs;
    private final double capacity;
    private final double reserve;
    private final int maxInFlight;
    private final int vodMaxInFlight;
    private final long retryAfterMs;

    private double tokens;
    private long lastNs;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Counter shedLive;
    private final Counter shedVod;

    public Admission(AdServeProperties.Shedding s, MeterRegistry registry) {
        this.enabled = s != null && s.enabled();
        double cps = s == null ? 1 : s.capacityPerSecond();
        this.ratePerNs = cps / 1e9;
        this.capacity = Math.max(1, cps * (s == null ? 1 : s.burstSeconds()));
        this.reserve = capacity * (s == null ? 0 : s.liveReserveFraction());
        this.maxInFlight = s == null ? Integer.MAX_VALUE : s.maxInFlight();
        this.vodMaxInFlight = (int) Math.max(1, maxInFlight * (1 - (s == null ? 0 : s.liveReserveFraction())));
        this.retryAfterMs = s == null ? 1000 : s.retryAfterMs();
        this.tokens = capacity;
        this.lastNs = System.nanoTime();
        this.shedLive = Counter.builder("adserve.shed").tag("priority", "live").register(registry);
        this.shedVod = Counter.builder("adserve.shed").tag("priority", "vod").register(registry);
    }

    public boolean enabled() {
        return enabled;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    /** Call {@link #release()} after the decision if and only if this returns ADMIT. */
    public Outcome tryAcquire(Priority p) {
        if (!enabled) {
            inFlight.incrementAndGet();
            return Outcome.ADMIT;
        }
        boolean live = p == Priority.LIVE;
        int f = inFlight.incrementAndGet();
        if (f > (live ? maxInFlight : vodMaxInFlight) || !take(live)) {
            inFlight.decrementAndGet();
            (live ? shedLive : shedVod).increment();
            return Outcome.SHED;
        }
        return Outcome.ADMIT;
    }

    public void release() {
        inFlight.decrementAndGet();
    }

    private synchronized boolean take(boolean live) {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastNs) * ratePerNs);
        lastNs = now;
        double floor = live ? 0 : reserve;
        if (tokens - 1 >= floor) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    public int inFlight() {
        return inFlight.get();
    }
}
