package adserve.core.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * One serving node's budget view in a fleet of {@code nodes}. Between syncs the node sees the
 * global spend as of the last sync plus its own spend since. With {@code allowanceSplit}, it may
 * spend only its share of what remained at the sync (remaining / nodes), so the fleet cannot
 * overshoot a budget by more than one impression per node; without it, every node can spend the
 * whole remainder, which is how a fleet overspends. The truth ledger holds the real total.
 */
public final class FleetBudgetLedger extends BudgetLedger {
    private final BudgetLedger truth;
    private final int nodes;
    private final boolean allowanceSplit;
    private final ToLongFunction<String> budgetOf;
    private final Map<String, Long> synced = new HashMap<>();
    private final Map<String, Long> local = new HashMap<>();
    private long day = Long.MIN_VALUE;

    public FleetBudgetLedger(BudgetLedger truth, int nodes, boolean allowanceSplit, ToLongFunction<String> budgetOf) {
        this.truth = truth;
        this.nodes = nodes;
        this.allowanceSplit = allowanceSplit;
        this.budgetOf = budgetOf;
    }

    @Override
    public synchronized long spent(String campaignId, long d) {
        if (d != day) return 0;
        long s = synced.getOrDefault(campaignId, 0L);
        long l = local.getOrDefault(campaignId, 0L);
        if (!allowanceSplit || nodes == 1) return s + l;
        long budget = budgetOf.applyAsLong(campaignId);
        long allowance = Math.max(0, budget - s) / nodes;
        // Report spend such that remaining = allowance - local.
        return budget - Math.max(0, allowance - l);
    }

    @Override
    public synchronized void charge(String campaignId, long d, long micros) {
        if (d != day) {
            day = d;
            synced.clear();
            local.clear();
        }
        truth.charge(campaignId, d, micros);
        local.merge(campaignId, micros, Long::sum);
    }

    @Override
    public void reconcile(String campaignId, long d, long observed) {
        truth.reconcile(campaignId, d, observed);
    }

    public synchronized void sync(Iterable<String> campaignIds, long d) {
        day = d;
        local.clear();
        for (String c : campaignIds) synced.put(c, truth.spent(c, d));
    }
}
