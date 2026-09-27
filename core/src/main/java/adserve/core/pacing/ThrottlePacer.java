package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Probabilistic throttling after Agarwal, Ghosh, Wei and You, "Budget Pacing for Targeted Online
 * Advertisements at LinkedIn", KDD 2014. Each campaign has a pass-through rate. At every slot
 * boundary the spend of the slot that ended is compared with the budget allocated to it (the
 * remaining budget spread over the remaining slots by forecast traffic); the rate is multiplied
 * by (1 + step) when under-delivering and by (1 - step) when over-delivering.
 */
public final class ThrottlePacer implements Pacer {
    private final PacingPlan plan;
    private final double step;
    private final double minRate;
    private volatile double rate;
    private long allocatedForLastSlot = -1;

    public ThrottlePacer(PacingPlan plan, double initialRate, double step, double minRate) {
        this.plan = plan;
        this.rate = initialRate;
        this.step = step;
        this.minRate = minRate;
    }

    public ThrottlePacer(PacingPlan plan) {
        this(plan, 0.5, 0.1, 0.001);
    }

    @Override
    public PacerKind kind() {
        return PacerKind.THROTTLE;
    }

    @Override
    public double rate() {
        return rate;
    }

    @Override
    public void update(PacingState s) {
        if (allocatedForLastSlot >= 0) {
            double r = rate;
            if (s.lastSlotSpendMicros() > allocatedForLastSlot) {
                r *= 1.0 - step;
            } else if (s.lastSlotSpendMicros() < allocatedForLastSlot) {
                r *= 1.0 + step;
            }
            rate = Math.max(minRate, Math.min(1.0, r));
        }
        allocatedForLastSlot = Math.round(s.remainingMicros() * plan.shareOfRemaining(s.slot()));
        if (s.remainingMicros() == 0) rate = 0;
    }
}
