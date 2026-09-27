package adserve.core.pacing;

import adserve.core.model.PacerKind;

/** Baseline: always admits. The budget check in the policy stage is what eventually stops it. */
public final class UnpacedPacer implements Pacer {
    @Override
    public PacerKind kind() {
        return PacerKind.UNPACED;
    }

    @Override
    public double rate() {
        return 1.0;
    }

    @Override
    public void update(PacingState s) {}
}
