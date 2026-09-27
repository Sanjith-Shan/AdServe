package adserve.server.budget;

import adserve.beacons.RedisCounters;
import adserve.core.caps.Windows;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.SnapshotSource;
import adserve.core.model.Campaign;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayList;
import java.util.List;

/**
 * Once a second, off the decision path, raises each campaign's in-process spend to at least the
 * confirmed spend the beacon consumer has counted in Redis. A node that restarts, or that shares
 * campaigns with other nodes, converges on the real figure within a second.
 */
public class BudgetSync {
    private final RedisCounters counters;
    private final BudgetLedger ledger;
    private final SnapshotSource snapshots;
    private final boolean requestClock;
    private volatile long lastDayMs = System.currentTimeMillis();

    public BudgetSync(RedisCounters counters, BudgetLedger ledger, SnapshotSource snapshots, boolean requestClock) {
        this.counters = counters;
        this.ledger = ledger;
        this.snapshots = snapshots;
        this.requestClock = requestClock;
    }

    /** With the request clock, the "day" is the day of the most recent decision. */
    public void observe(long decisionTsMs) {
        lastDayMs = decisionTsMs;
    }

    @Scheduled(fixedDelay = 1000)
    public void sync() {
        CampaignSnapshot s = snapshots.current();
        if (s.size() == 0) return;
        long day = Windows.day(requestClock ? lastDayMs : System.currentTimeMillis());
        List<String> ids = new ArrayList<>(s.size());
        for (Campaign c : s.campaigns()) ids.add(c.id());
        try {
            long[] spent = counters.spend(ids, day);
            for (int i = 0; i < spent.length; i++) {
                if (spent[i] > 0) ledger.reconcile(ids.get(i), day, spent[i]);
            }
        } catch (RuntimeException e) {
            // Redis down: keep the local figure; the decision path is unaffected.
        }
    }
}
