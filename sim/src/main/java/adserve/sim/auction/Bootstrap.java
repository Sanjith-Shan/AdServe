package adserve.sim.auction;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * Paired percentile bootstrap over clusters (viewers). Each replicate draws as many clusters as
 * there are, uniformly with replacement, and sums both measures over the same draw, so the ratio
 * of the two totals keeps their correlation. Deterministic for a given seed: every replicate's
 * generator is split from the root in order before any replicate runs, so the thread count does
 * not change the answer.
 */
public final class Bootstrap {
    private Bootstrap() {}

    /** 95% percentile intervals for the total of {@code a}, the total of {@code b} and total a / total b. */
    public record Paired(int reps, long seed, int clusters,
                         double aLo, double aHi, double bLo, double bHi, double ratioLo, double ratioHi) {}

    public static Paired paired(long[] a, long[] b, int n, int reps, long seed) {
        if (n <= 0 || a.length < n || b.length < n) throw new IllegalArgumentException("need n clusters in both arrays");
        if (reps < 2) throw new IllegalArgumentException("reps must be at least 2");
        SplittableRandom root = new SplittableRandom(seed);
        SplittableRandom[] rs = new SplittableRandom[reps];
        for (int r = 0; r < reps; r++) rs[r] = root.split();
        double[] sa = new double[reps], sb = new double[reps], ratio = new double[reps];
        IntStream.range(0, reps).parallel().forEach(r -> {
            SplittableRandom g = rs[r];
            long ta = 0, tb = 0;
            for (int i = 0; i < n; i++) {
                int k = g.nextInt(n);
                ta += a[k];
                tb += b[k];
            }
            sa[r] = ta;
            sb[r] = tb;
            ratio[r] = tb == 0 ? Double.NaN : (double) ta / tb;
        });
        return new Paired(reps, seed, n, percentile(sa, 0.025), percentile(sa, 0.975),
                percentile(sb, 0.025), percentile(sb, 0.975), percentile(ratio, 0.025), percentile(ratio, 0.975));
    }

    /** Linear-interpolated percentile, {@code q} in [0, 1]. Sorts a copy. */
    static double percentile(double[] x, double q) {
        double[] y = x.clone();
        Arrays.sort(y);
        double pos = q * (y.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(y.length - 1, lo + 1);
        return y[lo] + (pos - lo) * (y[hi] - y[lo]);
    }
}
