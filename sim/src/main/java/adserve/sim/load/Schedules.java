package adserve.sim.load;

import java.util.function.LongUnaryOperator;

/** Arrival schedules. Each maps request index i to its intended send offset in nanoseconds. */
public final class Schedules {
    private Schedules() {}

    /**
     * A synchronized live break: {@code n} requests in {@code windowS} seconds with a gamma(k=2)
     * arrival density of scale {@code thetaS}, truncated to the window. Everyone watching reaches
     * the break within a couple of seconds of each other (players buffer differently), arrivals
     * peak at {@code thetaS} and tail off. Request i goes out at the (i + 0.5) / n quantile.
     */
    public static LongUnaryOperator liveBreak(int n, double windowS, double thetaS) {
        int steps = 20_000;
        double[] cdf = new double[steps + 1];
        double dt = windowS / steps, run = 0;
        for (int k = 1; k <= steps; k++) {
            double t = k * dt;
            run += (t / (thetaS * thetaS)) * Math.exp(-t / thetaS) * dt;
            cdf[k] = run;
        }
        for (int k = 0; k <= steps; k++) cdf[k] /= run;
        long[] at = new long[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double q = (i + 0.5) / n;
            while (k < steps && cdf[k] < q) k++;
            at[i] = (long) (k * dt * 1e9);
        }
        return i -> at[(int) i];
    }

    /** Peak arrival rate of {@link #liveBreak} per second: n times the truncated density's maximum. */
    public static double liveBreakPeakRate(int n, double windowS, double thetaS) {
        double norm = 1 - Math.exp(-windowS / thetaS) * (1 + windowS / thetaS);
        double peakDensity = (1 / thetaS) * Math.exp(-1); // at t = theta
        return n * peakDensity / norm;
    }
}
