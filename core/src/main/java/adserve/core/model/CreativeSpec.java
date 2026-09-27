package adserve.core.model;

/**
 * A video creative. {@code clickRate} comes from the logs (clicks over impressions for this
 * creative), never from a model.
 */
public record CreativeSpec(String id, int durationS, double clickRate) {
    public CreativeSpec {
        if (durationS != 15 && durationS != 30 && durationS != 60) {
            throw new IllegalArgumentException("duration must be 15, 30 or 60 seconds: " + durationS);
        }
        if (clickRate < 0 || clickRate > 1) throw new IllegalArgumentException("click rate out of range");
    }
}
