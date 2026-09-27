package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Baseline that knows the day's traffic in advance: the slot-allocation controller of
 * {@link SmartPacer}, handed the replay day's own eligible-traffic curve instead of a forecast
 * from the day before. The gap between it and {@link SmartPacer} is what forecast error costs.
 * It exists only in the simulator; no serving system has this information.
 */
public final class OraclePacer implements Pacer {
    private final SmartPacer inner;

    public OraclePacer(PacingPlan actualDay, double initialRate) {
        this.inner = new SmartPacer(actualDay, initialRate, 0.3, 4.0);
    }

    @Override
    public PacerKind kind() {
        return PacerKind.ORACLE;
    }

    @Override
    public double rate() {
        return inner.rate();
    }

    @Override
    public void update(PacingState s) {
        inner.update(s);
    }
}
