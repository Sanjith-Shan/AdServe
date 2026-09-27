package adserve.core.targeting;

/**
 * The request reduced to what compiled targeting reads: dictionary ids and bitsets. Built once per
 * request by {@link TargetingIndex#context}; every campaign predicate then evaluates against it
 * without allocating.
 */
public final class RequestContext {
    final int geo;
    final int deviceBit;
    final long[] segments;
    final int genre;

    RequestContext(int geo, int deviceBit, long[] segments, int genre) {
        this.geo = geo;
        this.deviceBit = deviceBit;
        this.segments = segments;
        this.genre = genre;
    }

    public int genre() {
        return genre;
    }
}
