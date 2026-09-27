package adserve.core.caps;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters in process memory, for tests and the offline simulators. {@code idempotent=false}
 * gives the naive counter (a bare increment per event, duplicates included) that experiment 3
 * compares against.
 */
public final class InMemoryCapStore implements CapStore {
    private final boolean idempotent;
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public InMemoryCapStore(boolean idempotent) {
        this.idempotent = idempotent;
    }

    @Override
    public CapCounts fetch(String viewerId, List<String> campaignIds, long nowMs) {
        int n = campaignIds.size();
        long[] day = new long[n];
        long[] week = new long[n];
        for (int i = 0; i < n; i++) {
            day[i] = get(CapKeys.day(viewerId, campaignIds.get(i), nowMs));
            week[i] = get(CapKeys.week(viewerId, campaignIds.get(i), nowMs));
        }
        return new CapCounts(day, week, get(CapKeys.viewerHour(viewerId, nowMs)), true);
    }

    @Override
    public void recordImpression(String viewerId, String campaignId, String eventId, long tsMs) {
        if (idempotent && !seen.add(CapKeys.event(viewerId, eventId))) return;
        incr(CapKeys.day(viewerId, campaignId, tsMs));
        incr(CapKeys.week(viewerId, campaignId, tsMs));
        incr(CapKeys.viewerHour(viewerId, tsMs));
    }

    private long get(String k) {
        AtomicLong v = counters.get(k);
        return v == null ? 0 : v.get();
    }

    private void incr(String k) {
        counters.computeIfAbsent(k, x -> new AtomicLong()).incrementAndGet();
    }

    public long dayCount(String viewerId, String campaignId, long tsMs) {
        return get(CapKeys.day(viewerId, campaignId, tsMs));
    }
}
