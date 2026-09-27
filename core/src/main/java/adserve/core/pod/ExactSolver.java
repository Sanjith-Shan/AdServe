package adserve.core.pod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Branch and bound over every rule, used as the baseline the heuristics are measured against.
 * Items are explored in density order; a node is pruned when its value plus an upper bound on the
 * rest (the smaller of the fractional knapsack over the remaining capacity and the sum of the most
 * valuable items that still fit in the remaining ad count) cannot beat the incumbent. If the node
 * budget runs out, {@link #lastProvedOptimal()} is false and experiment 4 excludes that break from
 * the gap statistics instead of calling a heuristic answer optimal.
 */
public final class ExactSolver implements PodSolver {
    private final long nodeLimit;
    private boolean provedOptimal;
    private long nodes;

    // Search state, valid during one solve().
    private Item[] items;
    private PodRules rules;
    private int[] advUsed;
    private int[] campUsed;
    private int[] catCount;
    private final List<Item> current = new ArrayList<>();
    private List<Item> best;
    private long bestValue;
    private long[] sortedValues;

    public ExactSolver(long nodeLimit) {
        this.nodeLimit = nodeLimit;
    }

    public ExactSolver() {
        this(20_000_000L);
    }

    @Override
    public String name() {
        return "exact";
    }

    public boolean lastProvedOptimal() {
        return provedOptimal;
    }

    public long lastNodes() {
        return nodes;
    }

    @Override
    public Pod solve(List<Item> candidates, PodRules r) {
        rules = r;
        items = candidates.stream().filter(i -> i.durationS() <= r.capacityS())
                .sorted((a, b) -> Double.compare(b.density(), a.density())).toArray(Item[]::new);
        int maxAdv = 0, maxCamp = 0, maxCat = 0;
        for (Item i : items) {
            maxAdv = Math.max(maxAdv, i.advertiser());
            maxCamp = Math.max(maxCamp, i.campaign());
            maxCat = Math.max(maxCat, i.category());
        }
        advUsed = new int[maxAdv + 1];
        campUsed = new int[maxCamp + 1];
        catCount = new int[maxCat + 1];
        sortedValues = Arrays.stream(items).mapToLong(Item::value).toArray();
        current.clear();
        best = List.of();
        bestValue = 0;
        nodes = 0;
        provedOptimal = true;
        if (r.maxAds() > 0 && items.length > 0) dfs(0, 0, 0);
        if (best.isEmpty()) return Pod.EMPTY;
        return Pod.of(Arrangement.order(best, r.separation()));
    }

    private void dfs(int idx, int duration, long value) {
        if (++nodes > nodeLimit) {
            provedOptimal = false;
            return;
        }
        int n = current.size();
        if (n >= Math.max(1, rules.minAds()) && value > bestValue && Arrangement.arrangeable(current, rules.separation())) {
            bestValue = value;
            best = new ArrayList<>(current);
        }
        if (idx >= items.length || n >= rules.maxAds()) return;
        if (value + bound(idx, rules.capacityS() - duration, rules.maxAds() - n) <= bestValue) return;

        for (int i = idx; i < items.length; i++) {
            Item it = items[i];
            if (duration + it.durationS() > rules.capacityS()) continue;
            if (advUsed[it.advertiser()] > 0 || campUsed[it.campaign()] > 0) continue;
            int cc = catCount[it.category()];
            if (rules.separation() == Separation.POD && cc > 0) continue;
            if (rules.separation() == Separation.ADJACENT && cc + 1 > (rules.maxAds() + 1) / 2) continue;
            // Re-check the bound with this item forced in: cheap and prunes most siblings.
            if (value + it.value() + bound(i + 1, rules.capacityS() - duration - it.durationS(),
                    rules.maxAds() - n - 1) <= bestValue) {
                continue;
            }
            advUsed[it.advertiser()]++;
            campUsed[it.campaign()]++;
            catCount[it.category()]++;
            current.add(it);
            dfs(i + 1, duration + it.durationS(), value + it.value());
            current.remove(current.size() - 1);
            catCount[it.category()]--;
            campUsed[it.campaign()]--;
            advUsed[it.advertiser()]--;
            if (!provedOptimal) return;
        }
    }

    // Upper bound on what items[from..] can add with `cap` seconds and `slots` ads left.
    private long bound(int from, int cap, int slots) {
        if (slots <= 0 || cap <= 0) return 0;
        double frac = 0;
        int left = cap;
        for (int i = from; i < items.length && left > 0; i++) {
            Item it = items[i];
            if (it.durationS() <= left) {
                frac += it.value();
                left -= it.durationS();
            } else {
                frac += it.density() * left;
                left = 0;
            }
        }
        // The `slots` most valuable remaining items (a partial selection over the density-sorted tail).
        long[] top = new long[slots];
        for (int i = from; i < items.length; i++) {
            long v = sortedValues[i];
            if (v > top[slots - 1]) {
                int p = slots - 1;
                while (p > 0 && top[p - 1] < v) {
                    top[p] = top[p - 1];
                    p--;
                }
                top[p] = v;
            }
        }
        long byCount = 0;
        for (long v : top) byCount += v;
        return Math.min((long) Math.ceil(frac), byCount);
    }
}
