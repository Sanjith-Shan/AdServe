package adserve.beacons;

import ads.v1.Beacon;
import ads.v1.EventType;
import ads.v1.Token;
import adserve.core.caps.EventIds;
import adserve.core.token.TokenCodec;
import io.lettuce.core.RedisFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * What the beacon consumer does with one beacon. The token is verified (HMAC) before anything is
 * trusted; only IMPRESSION beacons move counters. The frequency counter and the spend counter are
 * both written through the idempotent scripts, keyed by the stable event id of the impression, so
 * a beacon retried three times, or a beacon for an impression AdServe already counted at decision
 * time, counts once.
 */
public final class BeaconProcessor {
    private final TokenCodec tokens;
    private final RedisCounters counters;
    private final ads.v1.Region localRegion;

    public final LongAdder processed = new LongAdder();
    public final LongAdder impressions = new LongAdder();
    public final LongAdder badTokens = new LongAdder();
    public final LongAdder rerouted = new LongAdder();

    public BeaconProcessor(TokenCodec tokens, RedisCounters counters, ads.v1.Region localRegion) {
        this.tokens = tokens;
        this.counters = counters;
        this.localRegion = localRegion;
    }

    /** Returns the pending Redis writes (empty for beacons that move no counter). */
    public List<RedisFuture<?>> process(Beacon b) {
        processed.increment();
        Token t;
        try {
            t = tokens.decode(b.getToken());
        } catch (TokenCodec.InvalidTokenException e) {
            badTokens.increment();
            return List.of();
        }
        // The token says where the impression was served. A beacon that arrived in another
        // region is still counted here (the counters are global in this deployment), and the
        // mismatch is recorded, which is what a cross-region forwarder would act on.
        if (localRegion != null && t.getServingRegion() != localRegion) rerouted.increment();
        if (b.getType() != EventType.IMPRESSION) return List.of();
        impressions.increment();
        String eventId = EventIds.impression(t.getImpressionId());
        long ts = t.getIssuedTsMs();
        List<RedisFuture<?>> out = new ArrayList<>(2);
        RedisFuture<?> cap = counters.recordImpressionAsync(t.getViewerId(), t.getCampaignId(), eventId, ts);
        if (cap != null) out.add(cap);
        out.add(counters.recordSpendAsync(t.getCampaignId(), eventId, t.getPriceMicros(), ts));
        return out;
    }
}
