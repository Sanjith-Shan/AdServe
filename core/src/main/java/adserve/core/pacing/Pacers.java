package adserve.core.pacing;

import adserve.core.model.PacerKind;

/** Builds the pacer a campaign asked for. The oracle needs its forecast and is built by the simulator. */
public final class Pacers {
    private Pacers() {}

    public static Pacer create(PacerKind kind, PacingPlan plan) {
        return switch (kind) {
            case UNPACED -> new UnpacedPacer();
            case THROTTLE -> new ThrottlePacer(plan);
            case SMART -> new SmartPacer(plan);
            case PID -> new PidPacer(plan);
            case ORACLE -> throw new IllegalArgumentException("the oracle pacer needs the day's traffic; build it directly");
        };
    }
}
