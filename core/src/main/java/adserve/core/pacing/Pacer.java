package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Decides whether a campaign may take part in this request. {@link #admit} runs on the decision
 * path for every eligible campaign and must be cheap and thread-safe; {@link #update} runs once
 * per slot, from one thread at a time.
 */
public interface Pacer {
    PacerKind kind();

    /** @param u a uniform random number in [0, 1) drawn for this request and campaign */
    default boolean admit(double u) {
        return u < rate();
    }

    /**
     * Factor on the campaign's bid in this slot's auctions, in (0, 1]. Throttling pacers keep it
     * at 1 and gate by {@link #admit}; a bid-shading pacer admits everything and lowers it.
     */
    default double bidMultiplier() {
        return 1.0;
    }

    /** Current pass-through rate in [0, 1]. */
    double rate();

    void update(PacingState s);
}
