package adserve.server.budget;

import adserve.beacons.RedisCounters;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.DecisionEngine;
import adserve.core.engine.SnapshotSource;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.server.config.AdServeProperties;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every ten seconds, off the decision path, reads each creative's confirmed impressions and
 * completes from the beacon consumer's counters and hands the engine a skip rate per creative
 * (1 - completes / impressions) for the auction's quality term. Does nothing while the term is
 * off, which is the default. A creative with fewer than the minimum impressions has no rate yet.
 */
public class QualitySync {
    private final RedisCounters counters;
    private final DecisionEngine engine;
    private final SnapshotSource snapshots;
    private final AdServeProperties.Auction auction;

    public QualitySync(RedisCounters counters, DecisionEngine engine, SnapshotSource snapshots,
                       AdServeProperties.Auction auction) {
        this.counters = counters;
        this.engine = engine;
        this.snapshots = snapshots;
        this.auction = auction;
    }

    @Scheduled(fixedDelay = 10_000)
    public void sync() {
        if (auction.qualityWeight() <= 0) return;
        CampaignSnapshot s = snapshots.current();
        List<String> ids = new ArrayList<>();
        for (Campaign c : s.campaigns()) for (CreativeSpec cr : c.creatives()) ids.add(cr.id());
        if (ids.isEmpty()) return;
        try {
            long[][] q = counters.creativeQuality(ids);
            Map<String, Double> skip = new HashMap<>();
            for (int i = 0; i < ids.size(); i++) {
                long imps = q[i][0];
                if (imps < auction.qualityMinImpressions()) continue;
                skip.put(ids.get(i), Math.max(0.0, 1.0 - (double) q[i][1] / imps));
            }
            Map<String, Double> frozen = Map.copyOf(skip);
            engine.setQualitySignal(id -> frozen.getOrDefault(id, 0.0));
        } catch (RuntimeException e) {
            // Redis down: keep the last rates; the decision path is unaffected.
        }
    }
}
