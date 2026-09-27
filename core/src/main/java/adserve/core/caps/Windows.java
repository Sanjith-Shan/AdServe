package adserve.core.caps;

/** UTC windows the counters are keyed by. */
public final class Windows {
    private Windows() {}

    public static final long HOUR_MS = 3_600_000L;
    public static final long DAY_MS = 86_400_000L;

    public static long day(long tsMs) {
        return Math.floorDiv(tsMs, DAY_MS);
    }

    public static long week(long tsMs) {
        return Math.floorDiv(day(tsMs), 7);
    }

    public static long hour(long tsMs) {
        return Math.floorDiv(tsMs, HOUR_MS);
    }
}
