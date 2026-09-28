package adserve.core.engine;

import adserve.core.auction.AuctionConfig;
import adserve.core.caps.CapMode;
import adserve.core.pod.Separation;

/**
 * Serving knobs.
 *
 * @param useRequestClock  decide "now" from the request's ts_ms (replays and simulation) instead
 *                         of the wall clock
 * @param pacingSlotMs     length of a pacing slot; pacers update at each boundary
 * @param auction          pricing rule, reserve and the optional quality term
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
        boolean logCandidates,
        AuctionConfig auction) {

    public static EngineConfig defaults() {
        return new EngineConfig(CapMode.UNKNOWN_ALLOW, 1, 6, Separation.ADJACENT, 12, true, 60_000L,
                ads.v1.Region.US_EAST, true, AuctionConfig.defaults());
    }

    public EngineConfig withCapMode(CapMode m) {
        return new EngineConfig(m, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, logCandidates, auction);
    }

    public EngineConfig withSeparation(Separation s) {
        return new EngineConfig(capMode, minAds, maxAds, s, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, logCandidates, auction);
    }

    public EngineConfig withLogCandidates(boolean b) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, b, auction);
    }

    public EngineConfig withServingRegion(ads.v1.Region r) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, r, logCandidates, auction);
    }

    public EngineConfig withRequestClock(boolean b) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, b,
                pacingSlotMs, servingRegion, logCandidates, auction);
    }

    public EngineConfig withAuction(AuctionConfig a) {
        return new EngineConfig(capMode, minAds, maxAds, separation, maxAdsPerViewerHour, useRequestClock,
                pacingSlotMs, servingRegion, logCandidates, a);
    }
}
