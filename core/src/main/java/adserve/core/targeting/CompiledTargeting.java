package adserve.core.targeting;

/**
 * One campaign's targeting compiled to bit operations. {@link #matches} is the hot loop of the
 * targeting stage: it reads final fields and the request's bitsets and allocates nothing.
 */
public final class CompiledTargeting {
    private final boolean anyGeo;
    private final long[] geos;
    private final int deviceMask; // 0 means any device
    private final boolean anySegment;
    private final long[] segments;
    private final long[] excludedGenres;

    CompiledTargeting(boolean anyGeo, long[] geos, int deviceMask, boolean anySegment, long[] segments,
                      long[] excludedGenres) {
        this.anyGeo = anyGeo;
        this.geos = geos;
        this.deviceMask = deviceMask;
        this.anySegment = anySegment;
        this.segments = segments;
        this.excludedGenres = excludedGenres;
    }

    public boolean matches(RequestContext ctx) {
        if (!anyGeo && !Bits.get(geos, ctx.geo)) return false;
        if (deviceMask != 0 && (deviceMask & ctx.deviceBit) == 0) return false;
        if (!anySegment && !Bits.intersects(segments, ctx.segments)) return false;
        return !Bits.get(excludedGenres, ctx.genre);
    }
}
