package adserve.core.pacing;

import adserve.core.model.PacerKind;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bid-scaling pacer against a toy second-price auction: 100 slots, 100 requests a slot, the
 * campaign values every impression at 1000 and the best rival's bid is uniform on [0, 1000). The
 * campaign wins when its shaded bid beats the rival and pays the rival's bid. Unpaced it would
 * spend about 50,000 a slot; the budget is 100,000 for the day (1,000 a slot), so the pacer has to
 * find lambda near sqrt(2 / 100) = 0.14.
 */
class BidShadingPacerTest {
    static final int SLOTS = 100, REQUESTS = 100;
    static final long VALUE = 1000, BUDGET = 100_000;

    record Result(long spent, long wins, double worstGap, double finalLambda) {}

    /** Runs the toy day; a throttling pacer is run the same way, gated by admit at full bid. */
    static Result run(Pacer p) {
        PacingPlan plan = PacingPlan.flat(SLOTS);
        Random r = new Random(11);
        long spent = 0, last = 0, wins = 0;
        double worst = 0;
        for (int s = 0; s < SLOTS; s++) {
            p.update(new PacingState(s, BUDGET, spent, last, REQUESTS));
            long slotSpend = 0;
            for (int i = 0; i < REQUESTS; i++) {
                long rival = (long) (r.nextDouble() * VALUE);
                double u = r.nextDouble();
                if (!p.admit(u)) continue;
                long bid = Math.round(VALUE * p.bidMultiplier());
                if (bid <= rival || spent + slotSpend + rival > BUDGET) continue;
                slotSpend += rival;
                wins++;
            }
            spent += slotSpend;
            last = slotSpend;
            worst = Math.max(worst, Math.abs((double) spent / BUDGET - plan.cumulative(s)));
        }
        return new Result(spent, wins, worst, p.bidMultiplier());
    }

    @Test
    void neverThrottles() {
        BidShadingPacer p = new BidShadingPacer(PacingPlan.flat(SLOTS), 0.3);
        assertThat(p.kind()).isEqualTo(PacerKind.BID_SCALE);
        assertThat(p.kind().wire()).isEqualTo("bid_scale");
        for (double u : new double[]{0.0, 0.5, 0.999999}) assertThat(p.admit(u)).isTrue();
        assertThat(p.rate()).isEqualTo(1.0);
    }

    @Test
    void tracksPlanInSecondPriceAuction() {
        Result r = run(new BidShadingPacer(PacingPlan.flat(SLOTS), 0.02));
        assertThat(r.spent()).as("delivered").isGreaterThan((long) (BUDGET * 0.9));
        assertThat(r.spent()).as("never over").isLessThanOrEqualTo(BUDGET);
        assertThat(r.worstGap()).as("worst gap to plan").isLessThan(0.15);
        assertThat(r.finalLambda()).isBetween(0.08, 0.25);
    }

    @Test
    void buysMoreImpressionsThanThrottlingForTheSameBudget() {
        // Throttling at full bid wins the expensive auctions too; shading the bid skips them.
        Result shaded = run(new BidShadingPacer(PacingPlan.flat(SLOTS), 0.02));
        Result throttled = run(new SmartPacer(PacingPlan.flat(SLOTS), 0.02, 0.3, 4.0));
        assertThat(throttled.spent()).isGreaterThan((long) (BUDGET * 0.9));
        double shadedPerWin = (double) shaded.spent() / shaded.wins();
        double throttledPerWin = (double) throttled.spent() / throttled.wins();
        assertThat(shadedPerWin).isLessThan(throttledPerWin * 0.5);
    }

    @Test
    void multiplierStaysInItsBounds() {
        BidShadingPacer p = new BidShadingPacer(PacingPlan.flat(SLOTS), 0.5, 0.5, Math.log(4), 0.01);
        // Massive overspend every slot drives lambda to the floor, never below.
        p.update(new PacingState(0, BUDGET, 0, 0, 0));
        for (int s = 1; s < 30; s++) p.update(new PacingState(s, BUDGET, 0, BUDGET * 10, 100));
        assertThat(p.bidMultiplier()).isEqualTo(0.01);
        // Nothing spent for many slots drives it to 1, never above.
        for (int s = 30; s < 60; s++) p.update(new PacingState(s, BUDGET, 0, 0, 100));
        assertThat(p.bidMultiplier()).isEqualTo(1.0);
    }

    @Test
    void factoryBuildsIt() {
        Pacer p = Pacers.create(PacerKind.BID_SCALE, PacingPlan.flat(SLOTS), 0.2);
        assertThat(p).isInstanceOf(BidShadingPacer.class);
        assertThat(p.bidMultiplier()).isEqualTo(0.2);
    }
}
