package adserve.core.caps;

/**
 * Counter key layout. Every key for one viewer shares the hash tag {@code {viewer}} so the
 * idempotent script's keys land in one Redis Cluster slot.
 */
public final class CapKeys {
    private CapKeys() {}

    public static String day(String viewer, String campaign, long tsMs) {
        return "fc:{" + viewer + "}:" + campaign + ":d" + Windows.day(tsMs);
    }

    public static String week(String viewer, String campaign, long tsMs) {
        return "fc:{" + viewer + "}:" + campaign + ":w" + Windows.week(tsMs);
    }

    public static String viewerHour(String viewer, long tsMs) {
        return "al:{" + viewer + "}:h" + Windows.hour(tsMs);
    }

    public static String event(String viewer, String eventId) {
        return "ev:{" + viewer + "}:" + eventId;
    }

    /** Seconds each key lives. Long enough to outlast its window plus late beacons. */
    public static final long DAY_TTL_S = 2 * 86_400L;
    public static final long WEEK_TTL_S = 8 * 86_400L;
    public static final long HOUR_TTL_S = 2 * 3_600L;
    public static final long EVENT_TTL_S = 8 * 86_400L;
}
