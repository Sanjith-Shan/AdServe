package adserve.core.pacing;

import adserve.core.model.Campaign;
import adserve.core.model.PacerKind;

/** Builds the pacer a campaign asked for. The oracle needs its forecast and is built by the simulator. */
public final class Pacers {
    private Pacers() {}

    public static Pacer create(PacerKind kind, PacingPlan plan) {
        return create(kind, plan, 0.5);
    }

    public static Pacer create(PacerKind kind, PacingPlan plan, double initialRate) {
        return switch (kind) {
            case UNPACED -> new UnpacedPacer();
            case THROTTLE -> new ThrottlePacer(plan, initialRate, 0.1, 1e-4);
            case SMART -> new SmartPacer(plan, initialRate, 0.3, 4.0);
            case PID -> new PidPacer(plan, initialRate, 0.5, 0.05, 0.1);
            case BID_SCALE -> new BidShadingPacer(plan, initialRate);
            case ORACLE -> throw new IllegalArgumentException("the oracle pacer needs the day's traffic; build it directly");
        };
    }

    /**
     * Forecast warm start: the pass-through rate that would spend exactly the budget if the
     * campaign won every eligible request the forecast expects at its best creative's price.
     * It is a lower bound on the rate needed (the campaign does not win every request), and the
     * feedback loop raises it from there. Without it every pacer starts at 0.5, and a small
     * budget facing heavy traffic is gone before the first slot boundary (BUG_LOG.md, bug 4).
     */
    public static double warmStart(Campaign c, PacingPlan plan, long maxImpressionValueMicros) {
        double fullRate = plan.totalWeight() * maxImpressionValueMicros;
        if (fullRate <= 0) return 0.5;
        return Math.max(1e-4, Math.min(1.0, c.dailyBudgetMicros() / fullRate));
    }
}
