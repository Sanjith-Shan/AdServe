package adserve.core.pod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Greedy by value per second with repair. Candidates are taken in density order whenever they
 * keep every rule satisfied. If the result has fewer than {@code minAds} items, the repair step
 * drops the longest item and refills with the shortest feasible ones until the minimum is met or
 * nothing changes. With {@code localSearch}, a 1-swap pass then replaces any item with an
 * unselected one whenever that raises the pod's value.
 */
public final class GreedySolver implements PodSolver {
    private static final Comparator<Item> BY_DENSITY = Comparator
            .comparingDouble(Item::density).reversed()
            .thenComparing(Comparator.comparingLong(Item::value).reversed());

    private final boolean localSearch;

    public GreedySolver(boolean localSearch) {
        this.localSearch = localSearch;
    }

    @Override
    public String name() {
        return localSearch ? "greedy_swap" : "greedy";
    }

    @Override
    public Pod solve(List<Item> candidates, PodRules rules) {
        List<Item> sorted = new ArrayList<>(candidates);
        sorted.sort(BY_DENSITY);
        Selection s = new Selection();
        for (Item i : sorted) {
            if (s.canAdd(i, rules)) s.add(i);
        }
        repairMinimum(s, sorted, rules);
        if (localSearch) improve(s, sorted, rules);
        return s.toPod(rules);
    }

    static void repairMinimum(Selection s, List<Item> sorted, PodRules rules) {
        if (s.items.size() >= rules.minAds()) return;
        List<Item> shortestFirst = new ArrayList<>(sorted);
        shortestFirst.sort(Comparator.comparingInt(Item::durationS).thenComparing(BY_DENSITY));
        for (int guard = 0; guard < 2 * rules.maxAds() + 2 && s.items.size() < rules.minAds(); guard++) {
            for (Item i : shortestFirst) {
                if (!s.contains(i) && s.canAdd(i, rules)) s.add(i);
                if (s.items.size() >= rules.minAds()) return;
            }
            if (s.items.isEmpty()) return;
            Item longest = s.items.stream().max(Comparator.comparingInt(Item::durationS)
                    .thenComparing(Comparator.comparingLong(Item::value))).orElseThrow();
            if (longest.durationS() == shortestFirst.get(0).durationS()) return; // nothing shorter to trade for
            s.remove(longest);
        }
    }

    static void improve(Selection s, List<Item> sorted, PodRules rules) {
        boolean changed = true;
        for (int round = 0; changed && round < 16; round++) {
            changed = false;
            for (Item u : sorted) {
                if (s.contains(u)) continue;
                if (s.canAdd(u, rules)) {
                    s.add(u);
                    changed = true;
                    continue;
                }
                for (Item out : new ArrayList<>(s.items)) {
                    if (u.value() <= out.value()) continue;
                    s.remove(out);
                    if (s.canAdd(u, rules)) {
                        s.add(u);
                        changed = true;
                        break;
                    }
                    s.add(out);
                }
            }
        }
    }
}
