package adserve.core.caps;

/**
 * What one counter fetch returned: per-campaign day and week counts aligned with the campaign
 * list that was asked for, plus the viewer's ad count this hour.
 */
public record CapCounts(long[] day, long[] week, long viewerHour, boolean known) {
    public static CapCounts unknown(int n) {
        return new CapCounts(new long[n], new long[n], 0, false);
    }
}
