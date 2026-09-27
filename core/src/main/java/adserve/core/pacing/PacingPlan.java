package adserve.core.pacing;

/**
 * The day split into equal slots with a forecast traffic weight per slot. The plan for a campaign
 * is "spend in proportion to forecast traffic": by the end of slot t it should have spent
 * {@code cumulative(t)} of its budget.
 */
public final class PacingPlan {
    private final double[] weight;
    private final double[] cumulative;
    private final long slotMs;

    public PacingPlan(double[] weight, long dayMs) {
        if (weight.length == 0) throw new IllegalArgumentException("empty plan");
        this.weight = weight.clone();
        this.cumulative = new double[weight.length];
        double total = 0;
        for (double w : weight) {
            if (w < 0) throw new IllegalArgumentException("negative weight");
            total += w;
        }
        double run = 0;
        for (int i = 0; i < weight.length; i++) {
            run += total > 0 ? weight[i] / total : 1.0 / weight.length;
            cumulative[i] = run;
        }
        cumulative[weight.length - 1] = 1.0;
        this.slotMs = dayMs / weight.length;
    }

    /** A flat plan with {@code slots} equal slots over a 24-hour day. */
    public static PacingPlan flat(int slots) {
        double[] w = new double[slots];
        java.util.Arrays.fill(w, 1.0);
        return new PacingPlan(w, 86_400_000L);
    }

    public int slots() {
        return weight.length;
    }

    public long slotMs() {
        return slotMs;
    }

    public double weight(int slot) {
        return weight[clamp(slot)];
    }

    /** Planned fraction of budget spent by the end of {@code slot}. */
    public double cumulative(int slot) {
        if (slot < 0) return 0;
        return cumulative[clamp(slot)];
    }

    /** Share of the remaining day's forecast that falls in {@code slot}, given we are at its start. */
    public double shareOfRemaining(int slot) {
        double before = slot <= 0 ? 0 : cumulative(slot - 1);
        double remaining = 1.0 - before;
        if (remaining <= 1e-12) return 1.0;
        return (cumulative(slot) - before) / remaining;
    }

    private int clamp(int slot) {
        return Math.max(0, Math.min(weight.length - 1, slot));
    }
}
