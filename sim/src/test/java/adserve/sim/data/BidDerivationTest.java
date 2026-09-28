package adserve.sim.data;

import adserve.core.model.Pricing;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BidDerivationTest {

    @Test
    void medianOfOddAndEvenCounts() {
        BidDerivation.Histogram h = new BidDerivation.Histogram();
        for (int v : new int[]{70, 5, 300, 20, 90}) h.add(v);
        assertThat(h.median()).isEqualTo(70);
        h.add(80);
        assertThat(h.median()).isEqualTo(75);
        assertThat(h.quantile(0.0)).isEqualTo(5);
        assertThat(h.quantile(1.0)).isEqualTo(300);
        assertThat(h.shareBelow(70)).isEqualTo(2.0 / 6);
    }

    @Test
    void bidIsMedianPriceOverClickRateSoA30sSpotBidsTheMedian() {
        BidDerivation d = new BidDerivation();
        for (int i = 0; i < 99; i++) d.addPrice("c1-aaaa", 10 + i); // median 59 fen = 590 micros
        BidDerivation.Bid b = d.bid("c1-aaaa", 0.001);
        assertThat(b.fallback()).isFalse();
        assertThat(b.impressions()).isEqualTo(99);
        assertThat(b.medianPriceMicros()).isEqualTo(590);
        assertThat(b.p10PriceMicros()).isEqualTo(190);
        assertThat(b.p90PriceMicros()).isEqualTo(990);
        assertThat(b.cpcBidMicros()).isEqualTo(590_000);
        assertThat(Pricing.impressionValueMicros(b.cpcBidMicros(), 0.001, 30)).isEqualTo(590);
    }

    @Test
    void fallbackBelowThirtyPricesIsDeterministicAndFromTheFittedLogNormal() {
        BidDerivation d = new BidDerivation();
        for (int i = 0; i < 5000; i++) d.addPrice("c1-big", 20 + (i % 150));
        for (int i = 0; i < BidDerivation.MIN_OBSERVED - 1; i++) d.addPrice("c2-small", 500);

        BidDerivation.Bid a = d.bid("c2-small", 0.002);
        BidDerivation.Bid again = d.bid("c2-small", 0.002);
        assertThat(a.fallback()).isTrue();
        assertThat(again).isEqualTo(a);
        assertThat(a.priceBasisMicros()).isNotEqualTo(a.medianPriceMicros());
        assertThat(a.cpcBidMicros()).isEqualTo(Math.round(d.priceModel().draw("c2-small") / 0.002));

        // A fresh derivation over the same prices draws the same bid; another id draws another.
        BidDerivation d2 = new BidDerivation();
        for (int i = 0; i < 5000; i++) d2.addPrice("c1-big", 20 + (i % 150));
        for (int i = 0; i < BidDerivation.MIN_OBSERVED - 1; i++) d2.addPrice("c2-small", 500);
        assertThat(d2.bid("c2-small", 0.002)).isEqualTo(a);
        assertThat(d2.priceModel().draw("c3-other")).isNotEqualTo(d2.priceModel().draw("c2-small"));

        d.addPrice("c2-small", 500);
        assertThat(d.bid("c2-small", 0.002).fallback()).isFalse();
    }

    @Test
    void logNormalRecoversKnownParametersAndDrawsFollowIt() {
        // Prices spread as exp(N(ln 700, 0.8^2)) micros, binned to whole fen.
        BidDerivation.LogNormal truth = new BidDerivation.LogNormal(Math.log(700), 0.8);
        BidDerivation d = new BidDerivation();
        int n = 20_000;
        for (int i = 0; i < n; i++) d.addPrice("c" + i, (int) Math.round(truth.draw("s" + i) / 10));
        BidDerivation.LogNormal fit = d.priceModel();
        assertThat(fit.mu()).isCloseTo(Math.log(700), within(0.05));
        assertThat(fit.sigma()).isCloseTo(0.8, within(0.05));
        assertThat(fit.median()).isCloseTo(700, within(40.0));

        double[] draws = new double[n];
        for (int i = 0; i < n; i++) draws[i] = fit.draw("x" + i);
        java.util.Arrays.sort(draws);
        assertThat(draws[n / 2]).isCloseTo(fit.median(), within(fit.median() * 0.05));
        assertThat(draws[0]).isPositive();
    }

    @Test
    void zeroPricesAreLeftOutOfTheFit() {
        BidDerivation d = new BidDerivation();
        for (int i = 0; i < 100; i++) d.addPrice("c", 0);
        for (int i = 0; i < 100; i++) d.addPrice("c", 100);
        for (int i = 0; i < 100; i++) d.addPrice("c", 10);
        BidDerivation.LogNormal fit = d.priceModel();
        assertThat(fit.mu()).isCloseTo((Math.log(1000) + Math.log(100)) / 2, within(1e-9));
        assertThat(fit.sigma()).isCloseTo((Math.log(1000) - Math.log(100)) / 2, within(1e-9));
        assertThat(d.allPay().shareAt(0)).isCloseTo(1.0 / 3, within(1e-9));
    }

    @Test
    void campaignIdMatchesTheLoader() {
        assertThat(BidDerivation.campaignId("1458", "2abc9eaf57d17a96195af3f63c45dc72")).isEqualTo("c1458-2abc9eaf");
    }
}
