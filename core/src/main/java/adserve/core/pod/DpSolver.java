package adserve.core.pod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dynamic program over 15-second slots. Items are grouped by advertiser and the DP picks at most
 * one item per group (a multiple-choice knapsack), so capacity, the ad-count range and "one
 * creative per advertiser" are handled exactly. Competitive separation is not in the state; when
 * the DP's pod cannot be ordered legally, the pod is repaired: the lowest-value item of the
 * over-represented category is dropped and the pod refilled greedily. {@link #relaxedBound} is the
 * plain 0/1 knapsack over slots with no rule but capacity, which bounds every solver from above.
 */
public final class DpSolver implements PodSolver {
    public static final int SLOT_S = 15;

    @Override
    public String name() {
        return "dp";
    }

    @Override
    public Pod solve(List<Item> candidates, PodRules rules) {
        int units = rules.capacityS() / SLOT_S;
        int maxK = Math.min(rules.maxAds(), candidates.size());
        if (units == 0 || maxK == 0) return Pod.EMPTY;

        Map<Integer, List<Item>> groups = new HashMap<>();
        for (Item i : candidates) {
            if (i.durationS() % SLOT_S != 0 || i.durationS() > rules.capacityS()) continue;
            groups.computeIfAbsent(i.advertiser(), k -> new ArrayList<>()).add(i);
        }
        List<List<Item>> g = new ArrayList<>(groups.values());
        int G = g.size();
        long NEG = Long.MIN_VALUE / 4;
        // best[j][c][k]: best value from the first j groups using exactly c units and k items.
        long[][][] best = new long[G + 1][units + 1][maxK + 1];
        int[][][] choice = new int[G + 1][units + 1][maxK + 1]; // -1 = skip group, else item index in group
        for (long[][] a : best) for (long[] b : a) java.util.Arrays.fill(b, NEG);
        best[0][0][0] = 0;
        for (int j = 1; j <= G; j++) {
            List<Item> grp = g.get(j - 1);
            for (int c = 0; c <= units; c++) {
                for (int k = 0; k <= maxK; k++) {
                    long b = best[j - 1][c][k];
                    int ch = -1;
                    if (k > 0) {
                        for (int x = 0; x < grp.size(); x++) {
                            Item it = grp.get(x);
                            int w = it.durationS() / SLOT_S;
                            if (w > c) continue;
                            long prev = best[j - 1][c - w][k - 1];
                            if (prev == NEG) continue;
                            if (prev + it.value() > b) {
                                b = prev + it.value();
                                ch = x;
                            }
                        }
                    }
                    best[j][c][k] = b;
                    choice[j][c][k] = ch;
                }
            }
        }
        long top = NEG;
        int bc = -1, bk = -1;
        for (int c = 0; c <= units; c++) {
            for (int k = Math.max(1, rules.minAds()); k <= maxK; k++) {
                if (best[G][c][k] > top) {
                    top = best[G][c][k];
                    bc = c;
                    bk = k;
                }
            }
        }
        if (bc < 0 || top == NEG) return Pod.EMPTY;
        List<Item> picked = new ArrayList<>();
        for (int j = G, c = bc, k = bk; j > 0; j--) {
            int ch = choice[j][c][k];
            if (ch >= 0) {
                Item it = g.get(j - 1).get(ch);
                picked.add(it);
                c -= it.durationS() / SLOT_S;
                k -= 1;
            }
        }
        if (Arrangement.arrangeable(picked, rules.separation())) {
            return Pod.of(Arrangement.order(picked, rules.separation()));
        }
        return repair(picked, candidates, rules);
    }

    private Pod repair(List<Item> picked, List<Item> candidates, PodRules rules) {
        Selection s = new Selection();
        // Keep items in value order while they stay arrangeable; this drops the cheapest of the
        // over-represented category first.
        List<Item> byValue = new ArrayList<>(picked);
        byValue.sort((a, b) -> Long.compare(b.value(), a.value()));
        for (Item i : byValue) {
            if (s.canAdd(i, rules)) s.add(i);
        }
        List<Item> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(b.density(), a.density()));
        for (Item i : sorted) {
            if (!s.contains(i) && s.canAdd(i, rules)) s.add(i);
        }
        GreedySolver.repairMinimum(s, sorted, rules);
        GreedySolver.improve(s, sorted, rules);
        return s.toPod(rules);
    }

    /** Best value of any subset that fits the break, ignoring every other rule. */
    public static long relaxedBound(List<Item> candidates, int capacityS) {
        int units = capacityS / SLOT_S;
        long[] best = new long[units + 1];
        for (Item i : candidates) {
            int w = i.durationS() / SLOT_S;
            for (int c = units; c >= w; c--) best[c] = Math.max(best[c], best[c - w] + i.value());
        }
        return best[units];
    }
}
