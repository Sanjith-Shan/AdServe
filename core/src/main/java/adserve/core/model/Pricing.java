package adserve.core.model;

/**
 * How a creative's duration changes what one impression is worth. Longer spots cost more but
 * less per second, which is how video inventory is usually priced; the factors are an assumption
 * stated in DESIGN.md, not a measurement.
 */
public final class Pricing {
    private Pricing() {}

    public static double durationFactor(int durationS) {
        return switch (durationS) {
            case 15 -> 0.6;
            case 30 -> 1.0;
            case 60 -> 1.7;
            default -> throw new IllegalArgumentException("unsupported duration " + durationS);
        };
    }

    /** Value of one impression in micros: CPC bid times click rate, scaled for duration. */
    public static long impressionValueMicros(long cpcBidMicros, double clickRate, int durationS) {
        return Math.round(cpcBidMicros * clickRate * durationFactor(durationS));
    }
}
