package adserve.core.model;

public enum PacerKind {
    /** Serve whenever eligible until the budget is gone. Baseline. */
    UNPACED,
    /** Probabilistic throttling, Agarwal et al., KDD 2014 (LinkedIn). */
    THROTTLE,
    /** Slot allocation by forecast traffic with online adjustment, Xu et al., KDD 2015. */
    SMART,
    /** PID feedback on cumulative spend, ported from AdRankBench. */
    PID,
    /** Knows the day's eligible traffic in advance. Simulation-only baseline. */
    ORACLE;

    public String wire() {
        return name().toLowerCase();
    }
}
