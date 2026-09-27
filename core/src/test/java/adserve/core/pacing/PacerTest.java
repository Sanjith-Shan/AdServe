package adserve.core.pacing;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each pacer against a toy day: 100 slots, flat traffic, full-rate spend of 100 per slot, budget
 * 2000 (a fifth of what the traffic could absorb). A good pacer spends close to the budget and
 * close to the plan; unpaced spends it all in the first 20 slots.
 */
class PacerTest {
    static final int SLOTS = 100;
    static final long FULL = 100, BUDGET = 2000;

    record Result(long spent, double worstGap, int exhaustedAt) {}

    static Result run(Pacer p) {
        PacingPlan plan = PacingPlan.flat(SLOTS);
        long spent = 0, last = 0;
        double worst = 0;
        int exhausted = -1;
        java.util.Random r = new java.util.Random(7);
        for (int s = 0; s < SLOTS; s++) {
            p.update(new PacingState(s, BUDGET, spent, last, FULL));
            long slotSpend = 0;
            for (int i = 0; i < FULL; i++) { // 100 requests of value 1
                if (spent + slotSpend >= BUDGET) break;
                if (p.admit(r.nextDouble())) slotSpend++;
            }
            spent += slotSpend;
            last = slotSpend;
            if (exhausted < 0 && spent >= BUDGET) exhausted = s;
            worst = Math.max(worst, Math.abs((double) spent / BUDGET - plan.cumulative(s)));
        }
        return new Result(spent, worst, exhausted);
    }

    static void assertPaced(Supplier<Pacer> p) {
        assertPaced(p, 0.15);
    }

    static void assertPaced(Supplier<Pacer> p, double maxGap) {
        Result r = run(p.get());
        assertThat(r.spent()).as("delivered").isGreaterThan((long) (BUDGET * 0.9));
        assertThat(r.worstGap()).as("worst gap to plan").isLessThan(maxGap);
    }

    @Test
    void unpacedExhaustsEarly() {
        Result r = run(new UnpacedPacer());
        assertThat(r.exhaustedAt()).isLessThan(25);
    }

    @Test
    void throttleTracksPlan() {
        assertPaced(() -> new ThrottlePacer(PacingPlan.flat(SLOTS)));
    }

    @Test
    void smartTracksPlan() {
        assertPaced(() -> new SmartPacer(PacingPlan.flat(SLOTS)));
    }

    @Test
    void pidTracksPlan() {
        // The ported gains react slowly to the 0.5 warm start on this toy day (worst gap 0.17);
        // experiment 2 measures it on real traffic.
        assertPaced(() -> new PidPacer(PacingPlan.flat(SLOTS)), 0.2);
    }

    @Test
    void oracleTracksPlan() {
        assertPaced(() -> new OraclePacer(PacingPlan.flat(SLOTS), 0.5));
    }

    @Test
    void planShares() {
        PacingPlan p = new PacingPlan(new double[]{1, 1, 2}, 3);
        assertThat(p.cumulative(0)).isEqualTo(0.25);
        assertThat(p.shareOfRemaining(2)).isEqualTo(1.0);
        assertThat(p.shareOfRemaining(1)).isCloseTo(1.0 / 3, org.assertj.core.data.Offset.offset(1e-9));
    }
}
