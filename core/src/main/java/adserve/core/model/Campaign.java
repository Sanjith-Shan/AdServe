package adserve.core.model;

import java.util.List;

/**
 * A campaign as the serving path sees it. Money is in micro-units of the log's currency.
 *
 * <p>The bid is a cost per click. A creative's value for one impression is
 * {@code cpcBidMicros * clickRate * durationFactor(duration)}, which is the eCPM divided by 1000
 * and scaled for spot length (see {@link Pricing}).
 */
public record Campaign(
        String id,
        String advertiserId,
        String name,
        String category,
        long cpcBidMicros,
        long dailyBudgetMicros,
        long flightStartMs,
        long flightEndMs,
        PacerKind pacer,
        FrequencyCap cap,
        TargetingSpec targeting,
        List<CreativeSpec> creatives,
        boolean active) {

    public Campaign {
        creatives = List.copyOf(creatives);
        if (cpcBidMicros < 0 || dailyBudgetMicros < 0) throw new IllegalArgumentException("negative money");
        if (flightEndMs < flightStartMs) throw new IllegalArgumentException("flight ends before it starts");
    }

    public boolean inFlight(long nowMs) {
        return active && nowMs >= flightStartMs && nowMs < flightEndMs;
    }
}
