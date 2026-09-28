package adserve.sim.auction;

import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BootstrapTest {

    @Test
    void identicalClustersGiveAZeroWidthInterval() {
        long[] a = new long[500], b = new long[500];
        java.util.Arrays.fill(a, 7);
        java.util.Arrays.fill(b, 10);
        Bootstrap.Paired p = Bootstrap.paired(a, b, 500, 200, 1);
        assertThat(p.aLo()).isEqualTo(3500);
        assertThat(p.aHi()).isEqualTo(3500);
        assertThat(p.bLo()).isEqualTo(5000);
        assertThat(p.ratioLo()).isEqualTo(0.7);
        assertThat(p.ratioHi()).isEqualTo(0.7);
    }

    @Test
    void intervalCoversTheTotalAndMatchesTheNormalWidth() {
        SplittableRandom r = new SplittableRandom(3);
        int n = 20_000;
        long[] a = new long[n], b = new long[n];
        double sum = 0, sq = 0;
        for (int i = 0; i < n; i++) {
            a[i] = r.nextInt(1000);
            b[i] = a[i] * 2 + r.nextInt(10);
            sum += a[i];
            sq += (double) a[i] * a[i];
        }
        Bootstrap.Paired p = Bootstrap.paired(a, b, n, 400, 11);
        assertThat(p.aLo()).isLessThan(sum).isGreaterThan(0);
        assertThat(p.aHi()).isGreaterThan(sum);
        // The total's standard error is sqrt(n) * sd; a 95% interval is about 3.92 of them wide.
        double sd = Math.sqrt(sq / n - (sum / n) * (sum / n));
        double expected = 3.92 * Math.sqrt(n) * sd;
        assertThat(p.aHi() - p.aLo()).isCloseTo(expected, within(expected * 0.2));
        // b is almost exactly 2a per cluster, so the paired ratio a/b is tight around 0.5.
        assertThat(p.ratioLo()).isBetween(0.49, 0.5);
        assertThat(p.ratioHi()).isBetween(0.49, 0.5);
        assertThat(p.ratioHi() - p.ratioLo()).isLessThan(0.001);
    }

    @Test
    void sameSeedSameAnswerOnlyTheFirstNClustersCount() {
        long[] a = {5, 1, 9, 3, 7, 1_000_000};
        long[] b = {2, 2, 2, 2, 2, 1_000_000};
        Bootstrap.Paired x = Bootstrap.paired(a, b, 5, 300, 42);
        Bootstrap.Paired y = Bootstrap.paired(a, b, 5, 300, 42);
        assertThat(x).isEqualTo(y);
        assertThat(x.aHi()).isLessThanOrEqualTo(45); // never draws the sixth entry
        assertThat(x.bLo()).isEqualTo(10);
    }

    @Test
    void percentileInterpolates() {
        double[] x = {4, 1, 3, 2};
        assertThat(Bootstrap.percentile(x, 0)).isEqualTo(1);
        assertThat(Bootstrap.percentile(x, 1)).isEqualTo(4);
        assertThat(Bootstrap.percentile(x, 0.5)).isEqualTo(2.5);
    }
}
