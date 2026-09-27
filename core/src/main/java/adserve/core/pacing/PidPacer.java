package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * PID feedback on cumulative spend, ported from AdRankBench's pacing simulator
 * ({@code src/pacing/controller.py}, PIDPacer, gains kp 0.5, ki 0.05, kd 0.1). The error is the
 * planned cumulative spend fraction minus the actual fraction; the control output is added to the
 * throttle, which is clamped to [0, 1].
 *
 * <p>One change from the Python original: it warm-started at the plan's first-slot share, which at
 * minute slots is about 0.0007 and starves the first hour, so this port takes the same initial
 * rate as the other feedback pacers. Recorded in BUG_LOG.md.
 */
public final class PidPacer implements Pacer {
    private final PacingPlan plan;
    private final double kp;
    private final double ki;
    private final double kd;
    private volatile double rate;
    private double integral;
    private double prevError;

    public PidPacer(PacingPlan plan, double initialRate, double kp, double ki, double kd) {
        this.plan = plan;
        this.rate = initialRate;
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }

    public PidPacer(PacingPlan plan) {
        this(plan, 0.5, 0.5, 0.05, 0.1);
    }

    @Override
    public PacerKind kind() {
        return PacerKind.PID;
    }

    @Override
    public double rate() {
        return rate;
    }

    @Override
    public void update(PacingState s) {
        if (s.budgetMicros() <= 0 || s.remainingMicros() == 0) {
            rate = 0;
            return;
        }
        // Target by the end of the slot that just ended, compared with what was actually spent.
        double target = plan.cumulative(s.slot() - 1);
        double actual = (double) s.spentMicros() / s.budgetMicros();
        double error = target - actual;
        integral += error;
        double derivative = error - prevError;
        prevError = error;
        double adj = kp * error + ki * integral + kd * derivative;
        rate = Math.max(0.0, Math.min(1.0, rate + adj));
    }
}
