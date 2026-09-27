package adserve.core.engine;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spend per campaign per UTC day, held in process. The decision path reads it and charges it
 * optimistically when it serves; the server adds a periodic reconciliation with the counters the
 * beacon consumer writes (see the server's BudgetSync), so a restart or a second node does not
 * start from zero. Never a database write on the decision path.
 */
public class BudgetLedger {
    private static final class Day {
        final long day;
        final AtomicLong micros = new AtomicLong();

        Day(long day) {
            this.day = day;
        }
    }

    private final ConcurrentHashMap<String, Day> spent = new ConcurrentHashMap<>();

    private Day day(String campaignId, long day) {
        Day d = spent.get(campaignId);
        if (d != null && d.day == day) return d;
        return spent.compute(campaignId, (k, cur) -> cur != null && cur.day >= day ? cur : new Day(day));
    }

    public long spent(String campaignId, long day) {
        Day d = spent.get(campaignId);
        return d == null || d.day != day ? 0 : d.micros.get();
    }

    public void charge(String campaignId, long day, long micros) {
        Day d = day(campaignId, day);
        if (d.day == day) d.micros.addAndGet(micros);
    }

    /** Raises the local figure to at least {@code observed} (spend another node or the beacon path saw). */
    public void reconcile(String campaignId, long day, long observed) {
        Day d = day(campaignId, day);
        if (d.day == day) d.micros.accumulateAndGet(observed, Math::max);
    }
}
