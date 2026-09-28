package adserve.core.engine;

import adserve.core.caps.Windows;
import adserve.core.model.Campaign;
import adserve.core.pacing.Pacer;
import adserve.core.pacing.PacingPlan;
import adserve.core.pacing.PacingState;
import adserve.core.pacing.Pacers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Holds one pacer per campaign per day and drives their slot updates from the decision clock.
 * The request path only reads a volatile rate and bumps two adders; the slot boundary work runs
 * on whichever request thread first crosses it, under a lock no other request waits for.
 */
public final class PacingController {
    private final long slotMs;
    private final int slotsPerDay;
    private final Function<Campaign, PacingPlan> plans;
    private final BiFunction<Campaign, PacingPlan, Pacer> factory;
    private final BudgetLedger budget;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private volatile long currentSlotKey = Long.MIN_VALUE;
    private final Object advanceLock = new Object();

    static final class State {
        final Campaign campaign;
        final Pacer pacer;
        final long day;
        final LongAdder slotSpend = new LongAdder();
        final LongAdder slotEligible = new LongAdder();

        State(Campaign campaign, Pacer pacer, long day) {
            this.campaign = campaign;
            this.pacer = pacer;
            this.day = day;
        }
    }

    public PacingController(long slotMs, Function<Campaign, PacingPlan> plans,
                            BiFunction<Campaign, PacingPlan, Pacer> factory, BudgetLedger budget) {
        this.slotMs = slotMs;
        this.slotsPerDay = (int) (Windows.DAY_MS / slotMs);
        this.plans = plans;
        this.factory = factory;
        this.budget = budget;
    }

    public static PacingController standard(long slotMs, Function<Campaign, PacingPlan> plans, BudgetLedger budget) {
        return new PacingController(slotMs, plans,
                (c, p) -> Pacers.create(c.pacer(), p, Pacers.warmStart(c, p, maxValue(c))), budget);
    }

    /** Highest single-impression value among a campaign's creatives. */
    public static long maxValue(Campaign c) {
        long v = 0;
        for (var cr : c.creatives()) {
            v = Math.max(v, adserve.core.model.Pricing.impressionValueMicros(c.cpcBidMicros(), cr.clickRate(), cr.durationS()));
        }
        return v;
    }

    private State state(Campaign c, long day) {
        State s = states.get(c.id());
        if (s == null || s.day != day || s.campaign.pacer() != c.pacer()
                || s.campaign.dailyBudgetMicros() != c.dailyBudgetMicros()) {
            State fresh = new State(c, factory.apply(c, plans.apply(c)), day);
            states.put(c.id(), fresh);
            // First sight of a campaign mid-day: give the pacer the state it would have had.
            fresh.pacer.update(new PacingState(slotOf(lastNowMs), c.dailyBudgetMicros(),
                    budget.spent(c.id(), day), 0, 0));
            return fresh;
        }
        return s;
    }

    private volatile long lastNowMs;

    int slotOf(long nowMs) {
        return (int) (Math.floorMod(nowMs, Windows.DAY_MS) / slotMs);
    }

    /** Moves every pacer to the slot containing {@code nowMs}, once per boundary. */
    public void advance(long nowMs) {
        lastNowMs = nowMs;
        long key = Math.floorDiv(nowMs, slotMs);
        if (key <= currentSlotKey) return;
        synchronized (advanceLock) {
            if (key <= currentSlotKey) return;
            long prev = currentSlotKey;
            currentSlotKey = key;
            if (prev == Long.MIN_VALUE) return;
            long day = Windows.day(nowMs);
            int slot = slotOf(nowMs);
            // Slots with no traffic still count: step through at most one day of them.
            long steps = Math.min(key - prev, slotsPerDay);
            for (State s : states.values()) {
                if (s.day != day) continue;
                long spend = s.slotSpend.sumThenReset();
                long eligible = s.slotEligible.sumThenReset();
                for (long k = steps - 1; k >= 0; k--) {
                    int sl = slot - (int) k;
                    if (sl < 0) continue;
                    boolean last = k == 0;
                    s.pacer.update(new PacingState(sl, s.campaign.dailyBudgetMicros(),
                            budget.spent(s.campaign.id(), day), k == steps - 1 ? spend : 0,
                            k == steps - 1 ? eligible : 0));
                    if (last) break;
                }
            }
        }
    }

    public boolean admit(Campaign c, long day, double u) {
        State s = state(c, day);
        s.slotEligible.increment();
        return s.pacer.admit(u);
    }

    /** The factor on this campaign's bid in the current slot (1 unless its pacer shades bids). */
    public double bidMultiplier(Campaign c, long day) {
        return state(c, day).pacer.bidMultiplier();
    }

    public void recordSpend(Campaign c, long day, long micros) {
        state(c, day).slotSpend.add(micros);
    }

    public Pacer pacer(Campaign c, long day) {
        return state(c, day).pacer;
    }

    public double rate(String campaignId) {
        State s = states.get(campaignId);
        return s == null ? Double.NaN : s.pacer.rate();
    }

    public int slotsPerDay() {
        return slotsPerDay;
    }
}
