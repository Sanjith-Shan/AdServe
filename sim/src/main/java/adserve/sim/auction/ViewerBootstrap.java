package adserve.sim.auction;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Paired, seeded bootstrap over viewers for experiment 16. Every quantity is tallied per viewer
 * cluster: a viewer's id hashes to one of {@link #CLUSTERS} clusters, so a cluster is a random,
 * fixed set of whole viewers. Resampling clusters with replacement resamples viewers (clusters are
 * independent draws of independent viewers) while keeping the loop small; the same resampled
 * clusters are used for the condition and for the baseline, so the interval is on the paired
 * change.
 */
final class ViewerBootstrap {
    private ViewerBootstrap() {}

    static final int CLUSTERS = 4096;

    /** The viewer's cluster: a mixed 64-bit hash of the id, so neighbouring ids spread out. */
    static int cluster(String viewerId) {
        long h = 1125899906842597L;
        for (int i = 0; i < viewerId.length(); i++) h = 31 * h + viewerId.charAt(i);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) Math.floorMod(h, (long) CLUSTERS);
    }

    /** A point estimate and a 95% percentile interval. */
    record Interval(double point, double lo, double hi) {}

    /**
     * Relative change of a ratio of totals versus the baseline:
     * {@code (sum(num)/sum(den)) / (sum(num0)/sum(den0)) - 1}. A null {@code den} (and
     * {@code den0}) means the plain total, so {@code num = clicks} gives the change in clicks and
     * {@code num = cost, den = clicks} the change in cost per click.
     */
    static Interval relativeChange(double[] num, double[] den, double[] num0, double[] den0, int resamples, long seed) {
        int k = num.length;
        if (num0.length != k || (den != null && den.length != k) || (den0 != null && den0.length != k)) {
            throw new IllegalArgumentException("cluster arrays differ in length");
        }
        double point = stat(sum(num), den == null ? 1 : sum(den), sum(num0), den0 == null ? 1 : sum(den0));
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] draws = new double[resamples];
        int n = 0;
        for (int b = 0; b < resamples; b++) {
            double a = 0, d = 0, a0 = 0, d0 = 0;
            for (int j = 0; j < k; j++) {
                int c = rnd.nextInt(k);
                a += num[c];
                a0 += num0[c];
                if (den != null) d += den[c];
                if (den0 != null) d0 += den0[c];
            }
            double s = stat(a, den == null ? 1 : d, a0, den0 == null ? 1 : d0);
            if (Double.isFinite(s)) draws[n++] = s;
        }
        double[] ok = Arrays.copyOf(draws, n);
        Arrays.sort(ok);
        if (n == 0) return new Interval(point, Double.NaN, Double.NaN);
        return new Interval(point, quantile(ok, 0.025), quantile(ok, 0.975));
    }

    static double stat(double num, double den, double num0, double den0) {
        return (num / den) / (num0 / den0) - 1;
    }

    static double sum(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s;
    }

    /** Linear interpolation between order statistics of a sorted array. */
    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = Math.min(sorted.length - 1, lo + 1);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }
}
