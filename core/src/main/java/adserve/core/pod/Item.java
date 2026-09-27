package adserve.core.pod;

/**
 * One candidate creative for a pod. {@code advertiser} and {@code category} are small dense ids
 * assigned per request; {@code campaign} identifies the campaign so a pod never carries the same
 * campaign twice. {@code ref} lets the caller map the item back to its own objects.
 */
public record Item(int ref, int campaign, int advertiser, int category, int durationS, long value) {
    public double density() {
        return (double) value / durationS;
    }
}
