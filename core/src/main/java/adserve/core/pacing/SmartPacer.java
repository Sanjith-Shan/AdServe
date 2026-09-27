package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Slot allocation with online adjustment, after Xu, Lee, Li, Qi and Lu, "Smart Pacing for
 * Effective Online Ad Campaign Optimization", KDD 2015. The remaining budget is re-allocated
 * across the remaining slots in proportion to forecast traffic at every boundary. The rate for the
 * next slot is that slot's allocation divided by the spend expected at full rate, which is
 * estimated from the slot that just ended ({@code spend / rate}, smoothed) and scaled by the
 * forecast's shape.
 *
 * <p>Not implemented: the paper's layered rates by predicted response rate, which need a
 * per-request response prediction. AdServe has only per-creative click rates from the logs.
 */
public final class SmartPacer implements Pacer {
    private final PacingPlan plan;
    private final double smoothing;
    private final double maxChange;
    private volatile double rate;
    private double fullRateSpendPerWeight = -1; // smoothed estimate of spend at rate 1, per unit forecast weight

    public SmartPacer(PacingPlan plan, double initialRate, double smoothing, double maxChange) {
        this.plan = plan;
        this.rate = initialRate;
        this.smoothing = smoothing;
        this.maxChange = maxChange;
    }

    public SmartPacer(PacingPlan plan) {
        this(plan, 0.5, 0.3, 4.0);
    }

    @Override
    public PacerKind kind() {
        return PacerKind.SMART;
    }

    @Override
    public double rate() {
        return rate;
    }

    @Override
    public void update(PacingState s) {
        int last = s.slot() - 1;
        double r = rate;
        if (last >= 0 && r > 0 && plan.weight(last) > 0) {
            double observed = (s.lastSlotSpendMicros() / r) / plan.weight(last);
            fullRateSpendPerWeight = fullRateSpendPerWeight < 0
                    ? observed
                    : smoothing * observed + (1 - smoothing) * fullRateSpendPerWeight;
        }
        long remaining = s.remainingMicros();
        if (remaining == 0) {
            rate = 0;
            return;
        }
        double allocation = remaining * plan.shareOfRemaining(s.slot());
        double expectedAtFull = fullRateSpendPerWeight * plan.weight(s.slot());
        double next;
        if (fullRateSpendPerWeight <= 0 || expectedAtFull <= 0) {
            next = Math.min(1.0, r * 2); // nothing observed yet: probe upwards
        } else {
            next = allocation / expectedAtFull;
        }
        double lo = Math.max(1e-4, r / maxChange);
        double hi = Math.max(r * maxChange, 1e-3);
        rate = Math.max(0.0, Math.min(1.0, Math.max(lo, Math.min(hi, next))));
    }
}
