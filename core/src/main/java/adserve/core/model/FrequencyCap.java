package adserve.core.model;

/**
 * At most {@code perDay} impressions of a campaign per viewer per UTC day and at most
 * {@code perWeek} per 7-day window. Zero means no cap for that window.
 */
public record FrequencyCap(int perDay, int perWeek) {
    public static final FrequencyCap NONE = new FrequencyCap(0, 0);

    public FrequencyCap {
        if (perDay < 0 || perWeek < 0) throw new IllegalArgumentException("negative cap");
    }

    public boolean capped() {
        return perDay > 0 || perWeek > 0;
    }

    /** How many more impressions this viewer may see, given the current counts. */
    public int remaining(long dayCount, long weekCount) {
        long r = Integer.MAX_VALUE;
        if (perDay > 0) r = Math.min(r, perDay - dayCount);
        if (perWeek > 0) r = Math.min(r, perWeek - weekCount);
        return (int) Math.max(0, r);
    }
}
