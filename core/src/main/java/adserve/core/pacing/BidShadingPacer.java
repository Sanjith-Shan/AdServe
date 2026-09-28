package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Pacing by bid, not by throttle: the campaign enters every auction it is eligible for, and its
 * bid value is multiplied by {@code lambda} in (0, 1]. Lowering the bid loses the auctions where
 * the campaign's margin over the runner-up is thinnest (and, under second price, pays the same
 * rival-set price where it still wins), where a throttle skips auctions at random whatever their
 * price.
 *
 * <p>After the adaptive pacing of Balseiro and Gur, "Learning in Repeated Auctions with Budgets:
 * Regret Minimization and Equilibrium", Management Science 2019 (bid {@code v / (1 + mu)} with
 * the multiplier {@code mu} moved each period by spend against the per-period budget), and the
 * pacing multipliers of Conitzer, Kroer, Sodomka and Stier-Moses, "Multiplicative Pacing
 * Equilibria in Auction Markets", Operations Research 2022. Two changes from Balseiro and Gur:
 * the per-period target is not the flat {@code budget / T} but the allocation the {@link SmartPacer}
 * uses (the remaining budget spread over the remaining slots by forecast traffic), and the step is
 * multiplicative on {@code lambda} (a gradient step on {@code log lambda}) rather than additive on
 * {@code mu}, so the controller moves as fast in relative terms at {@code lambda = 0.02} as at 0.8.
 *
 * <p>The update at the start of every slot:
 *
 * <pre>
 *   ratio   = (spend_last + delta) / (allocation_last + delta)      delta = 2% of allocation_last + 1
 *   lambda *= exp(-gain * clamp(ln ratio, -maxLogStep, maxLogStep))
 *   lambda  = clamp(lambda, floor, 1)
 * </pre>
 *
 * with {@code allocation = remaining * plan.shareOfRemaining(slot)}. Re-allocating the remaining
 * budget at every boundary is the catch-up term: an under-spent morning raises every later
 * allocation, as it does for Smart Pacing. The warm start is the same forecast bound the
 * throttling pacers use ({@link Pacers#warmStart}): at {@code lambda = budget / (forecast
 * requests x best bid value)}, even winning every forecast request at a price no higher than the
 * shaded bid would spend at most the budget.
 */
public final class BidShadingPacer implements Pacer {
    public static final double DEFAULT_GAIN = 0.5;
    public static final double DEFAULT_MAX_LOG_STEP = Math.log(4);
    public static final double DEFAULT_FLOOR = 0.01;

    private final PacingPlan plan;
    private final double gain;
    private final double maxLogStep;
    private final double floor;
    private volatile double lambda;
    private long allocatedForLastSlot = -1;

    public BidShadingPacer(PacingPlan plan, double initialLambda, double gain, double maxLogStep, double floor) {
        if (floor <= 0 || floor > 1) throw new IllegalArgumentException("floor must be in (0, 1]");
        this.plan = plan;
        this.gain = gain;
        this.maxLogStep = maxLogStep;
        this.floor = floor;
        this.lambda = clamp(initialLambda);
    }

    public BidShadingPacer(PacingPlan plan, double initialLambda) {
        this(plan, initialLambda, DEFAULT_GAIN, DEFAULT_MAX_LOG_STEP, DEFAULT_FLOOR);
    }

    public BidShadingPacer(PacingPlan plan) {
        this(plan, 0.5);
    }

    @Override
    public PacerKind kind() {
        return PacerKind.BID_SCALE;
    }

    /** Never throttles: the budget check in the engine is what stops a spent campaign. */
    @Override
    public boolean admit(double u) {
        return true;
    }

    @Override
    public double rate() {
        return 1.0;
    }

    @Override
    public double bidMultiplier() {
        return lambda;
    }

    @Override
    public void update(PacingState s) {
        if (allocatedForLastSlot >= 0) {
            double delta = 0.02 * allocatedForLastSlot + 1;
            double logRatio = Math.log((s.lastSlotSpendMicros() + delta) / (allocatedForLastSlot + delta));
            logRatio = Math.max(-maxLogStep, Math.min(maxLogStep, logRatio));
            lambda = clamp(lambda * Math.exp(-gain * logRatio));
        }
        allocatedForLastSlot = Math.round(s.remainingMicros() * plan.shareOfRemaining(s.slot()));
    }

    private double clamp(double l) {
        return Math.max(floor, Math.min(1.0, l));
    }
}
