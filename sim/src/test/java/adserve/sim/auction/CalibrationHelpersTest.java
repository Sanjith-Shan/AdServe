package adserve.sim.auction;

import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import adserve.sim.auction.Miscalibration.Condition;
import adserve.sim.auction.ViewerBootstrap.Interval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CalibrationHelpersTest {

    static Campaign campaign(String id, String adv, double... rates) {
        List<CreativeSpec> cr = new ArrayList<>();
        for (int i = 0; i < rates.length; i++) cr.add(new CreativeSpec(id + "-" + i, 30, rates[i]));
        return new Campaign(id, adv, id, "retail", 1_000_000, 10_000_000, 0, Long.MAX_VALUE / 2, PacerKind.UNPACED,
                FrequencyCap.NONE, TargetingSpec.ANY, cr, true);
    }

    static final List<Campaign> CS = List.of(campaign("a", "advA", 0.001, 0.002), campaign("b", "advB", 0.004));

    @Test
    void baselineIsTheTruth() {
        assertThat(Miscalibration.predicted(CS, Condition.baseline())).isEqualTo(Miscalibration.truth(CS));
    }

    @Test
    void uniformScalesEveryCreative() {
        Map<String, Double> p = Miscalibration.predicted(CS, Condition.uniform(2.0));
        assertThat(p.get("a-0")).isCloseTo(0.002, within(1e-12));
        assertThat(p.get("b-0")).isCloseTo(0.008, within(1e-12));
    }

    @Test
    void advertiserBiasTouchesOnlyThatAdvertiser() {
        Map<String, Double> p = Miscalibration.predicted(CS, Condition.advertiser("advB", 0.5));
        assertThat(p.get("a-0")).isEqualTo(0.001);
        assertThat(p.get("a-1")).isEqualTo(0.002);
        assertThat(p.get("b-0")).isCloseTo(0.002, within(1e-12));
    }

    @Test
    void predictionsAreClampedToOne() {
        Map<String, Double> p = Miscalibration.predicted(List.of(campaign("c", "advC", 0.8)), Condition.uniform(2.0));
        assertThat(p.get("c-0")).isEqualTo(1.0);
    }

    @Test
    void noiseIsSeededAndMeanOne() {
        List<Campaign> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) many.add(campaign("c" + i, "adv", 0.01));
        Map<String, Double> p1 = Miscalibration.predicted(many, Condition.noise(0.5, 7));
        assertThat(Miscalibration.predicted(many, Condition.noise(0.5, 7))).isEqualTo(p1);
        assertThat(Miscalibration.predicted(many, Condition.noise(0.5, 8))).isNotEqualTo(p1);
        double mean = p1.values().stream().mapToDouble(v -> v / 0.01).average().orElseThrow();
        assertThat(mean).isCloseTo(1.0, within(0.02));
        double sdLog = Math.sqrt(p1.values().stream().mapToDouble(v -> Math.log(v / 0.01) + 0.125)
                .map(x -> x * x).average().orElseThrow());
        assertThat(sdLog).isCloseTo(0.5, within(0.02));
    }

    @Test
    void withPredictionsKeepsIdsAndSwapsRates() {
        List<Campaign> out = Miscalibration.withPredictions(CS, Miscalibration.predicted(CS, Condition.uniform(0.5)));
        assertThat(out.get(0).id()).isEqualTo("a");
        assertThat(out.get(0).creatives().get(1).id()).isEqualTo("a-1");
        assertThat(out.get(0).creatives().get(1).clickRate()).isCloseTo(0.001, within(1e-12));
        assertThat(out.get(1).cpcBidMicros()).isEqualTo(CS.get(1).cpcBidMicros());
    }

    @Test
    void repeatedCreativeIdFails() {
        assertThatThrownBy(() -> Miscalibration.truth(List.of(campaign("a", "x", 0.1), campaign("a", "y", 0.1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void perClickBillingCancelsAUniformBias() {
        // Under a uniform factor k the rival's score, hence the price, scales by k, and so does the
        // winner's predicted rate: price/predicted is unchanged.
        long price = 400;
        double truth = 0.002;
        double base = Miscalibration.perClickBilled(price, truth, truth);
        assertThat(base).isEqualTo(400.0);
        assertThat(Miscalibration.perClickBilled(price * 2, truth * 2, truth)).isCloseTo(base, within(1e-9));
        assertThat(Miscalibration.perClickBilled(price, 0, truth)).isZero();
    }

    @Test
    void bootstrapOfIdenticalRunsIsZeroWide() {
        double[] x = randomClusters(1);
        Interval iv = ViewerBootstrap.relativeChange(x, null, x, null, 500, 3);
        assertThat(iv.point()).isZero();
        assertThat(iv.lo()).isCloseTo(0, within(1e-12));
        assertThat(iv.hi()).isCloseTo(0, within(1e-12));
    }

    @Test
    void bootstrapRecoversAKnownShiftAndIsSeeded() {
        double[] base = randomClusters(1);
        double[] noise = randomClusters(2);
        double[] cond = new double[base.length];
        for (int i = 0; i < base.length; i++) cond[i] = 0.9 * base[i] + 0.05 * noise[i];
        Interval iv = ViewerBootstrap.relativeChange(cond, null, base, null, 1000, 5);
        assertThat(iv.lo()).isLessThan(iv.point());
        assertThat(iv.hi()).isGreaterThan(iv.point());
        assertThat(iv.point()).isBetween(iv.lo(), iv.hi());
        assertThat(iv.hi() - iv.lo()).isLessThan(0.02);
        assertThat(ViewerBootstrap.relativeChange(cond, null, base, null, 1000, 5)).isEqualTo(iv);
    }

    @Test
    void ratioStatisticIsCostPerClickChange() {
        double[] cost = {10, 20}, clicks = {1, 1}, cost0 = {5, 10}, clicks0 = {1, 1};
        Interval iv = ViewerBootstrap.relativeChange(cost, clicks, cost0, clicks0, 200, 1);
        assertThat(iv.point()).isCloseTo(1.0, within(1e-12));
        assertThat(iv.lo()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void clustersSpreadViewers() {
        int[] n = new int[ViewerBootstrap.CLUSTERS];
        for (int i = 0; i < 400_000; i++) n[ViewerBootstrap.cluster("viewer-" + i)]++;
        for (int c : n) assertThat(c).isBetween(50, 150);
        assertThat(ViewerBootstrap.cluster("abc")).isEqualTo(ViewerBootstrap.cluster("abc"));
    }

    @Test
    void quantileInterpolates() {
        assertThat(ViewerBootstrap.quantile(new double[]{0, 10}, 0.25)).isEqualTo(2.5);
    }

    @Test
    void replayCapStoreCountsLikeTheMapStore() {
        long day = 15867L * 86_400_000L;
        java.util.Map<String, Integer> viewers = java.util.Map.of("v1", 0, "v2", 1);
        java.util.Map<String, Integer> campaigns = java.util.Map.of("a", 0, "b", 1, "c", 2);
        CalibrationExperiment.ReplayCapStore replay = new CalibrationExperiment.ReplayCapStore(
                new CalibrationExperiment.ReplayViewers(viewers, 15867L), campaigns);
        adserve.core.caps.InMemoryCapStore map = new adserve.core.caps.InMemoryCapStore(false);
        SplittableRandom r = new SplittableRandom(4);
        String[] vs = {"v1", "v2"};
        String[] cs = {"a", "b", "c"};
        List<String> all = List.of("c", "a", "b");
        for (int i = 0; i < 2000; i++) {
            String v = vs[r.nextInt(2)];
            long ts = day + r.nextLong(86_400_000L);
            var x = replay.fetch(v, all, ts);
            var y = map.fetch(v, all, ts);
            assertThat(x.day()).containsExactly(y.day());
            assertThat(x.week()).containsExactly(y.week());
            assertThat(x.viewerHour()).isEqualTo(y.viewerHour());
            String c = cs[r.nextInt(3)];
            replay.recordImpression(v, c, "e" + i, ts);
            map.recordImpression(v, c, "e" + i, ts);
        }
        // A viewer seen once is never counted, and reads zero.
        replay.recordImpression("once", "a", "e", day);
        assertThat(replay.fetch("once", all, day).day()).containsOnly(0L);
        assertThatThrownBy(() -> replay.fetch("v1", all, day - 1)).isInstanceOf(IllegalStateException.class);
    }

    static double[] randomClusters(long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double[] x = new double[ViewerBootstrap.CLUSTERS];
        for (int i = 0; i < x.length; i++) x[i] = 1 + r.nextDouble();
        return x;
    }
}
