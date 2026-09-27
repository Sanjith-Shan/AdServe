package adserve.core.caps;

import java.util.List;

/**
 * Frequency-cap counters. The decision path makes exactly one {@link #fetch} per request; the
 * increments are fire-and-forget and idempotent on the event id, so the decision-time write and
 * the beacon consumer's write of the same impression count once.
 */
public interface CapStore {

    /**
     * Reads the day and week counters for {@code campaignIds} and the viewer's hourly ad count in
     * one round trip. Implementations throw {@link CapStoreUnavailableException} when the store
     * does not answer inside its deadline.
     */
    CapCounts fetch(String viewerId, List<String> campaignIds, long nowMs);

    /**
     * Counts one impression of {@code campaignId} for {@code viewerId}, once per {@code eventId}.
     * May complete asynchronously; never awaited on the decision path.
     */
    void recordImpression(String viewerId, String campaignId, String eventId, long tsMs);
}
