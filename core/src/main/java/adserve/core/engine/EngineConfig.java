package adserve.core.engine;

import adserve.core.caps.CapMode;
import adserve.core.pod.Separation;

/**
 * Serving knobs.
 *
 * @param useRequestClock  decide "now" from the request's ts_ms (replays and simulation) instead
 *                         of the wall clock
 * @param pacingSlotMs     length of a pacing slot; pacers update at each boundary
 */
public record EngineConfig(
        CapMode capMode,
        int minAds,
        int maxAds,
        Separation separation,
        int maxAdsPerViewerHour,
        boolean useRequestClock,
        long pacingSlotMs,
        ads.v1.Region servingRegion,
        boolean logCandidates) {

    public static EngineConfig defaults() {
        return new EngineConfig(CapMode.UNKNOWN_ALLOW, 1, 6, Separation.ADJACENT, 12, true, 60_000L,
                ads.v1.Region.US_EAST, true);
    }

    public EngineConfig withCapMode(CapMode m) {
        return new EngineConfig(m, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, logCandidates);
    }

    public EngineConfig withSeparation(Separation s) {
        return new EngineConfig(capMode, minAds, maxAds, s, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, logCandidates);
    }

    public EngineConfig withLogCandidates(boolean b) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, b);
    }

    public EngineConfig withServingRegion(ads.v1.Region r) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, r, logCandidates);
    }

    public EngineConfig withRequestClock(boolean b) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, b,
                pacingSlotMs, servingRegion, logCandidates);
    }
}
