package adserve.core.pod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Ordering rules shared by every solver. */
public final class Arrangement {
    private Arrangement() {}

    /**
     * A multiset of categories can be ordered with no two equal neighbours iff the most common
     * category appears at most ceil(n / 2) times.
     */
    public static boolean arrangeable(List<Item> items, Separation sep) {
        if (sep == Separation.NONE || items.size() <= 1) return true;
        Map<Integer, Integer> counts = new HashMap<>();
        int max = 0;
        for (Item i : items) max = Math.max(max, counts.merge(i.category(), 1, Integer::sum));
        if (sep == Separation.POD) return max <= 1;
        return max <= (items.size() + 1) / 2;
    }

    /**
     * Orders a feasible selection: the most valuable item first (the first slot of a break is the
     * one most viewers see), then at each position the most valuable remaining item whose category
     * differs from the previous one and whose removal keeps the rest arrangeable.
     */
    public static List<Item> order(List<Item> selection, Separation sep) {
        List<Item> rest = new ArrayList<>(selection);
        rest.sort((a, b) -> Long.compare(b.value(), a.value()));
        if (sep != Separation.ADJACENT) return rest;
        List<Item> out = new ArrayList<>(rest.size());
        int prev = Integer.MIN_VALUE;
        while (!rest.isEmpty()) {
            int pick = -1;
            for (int i = 0; i < rest.size(); i++) {
                Item c = rest.get(i);
                if (c.category() == prev) continue;
                if (canFollow(rest, i)) {
                    pick = i;
                    break;
                }
            }
            if (pick < 0) throw new IllegalStateException("selection is not arrangeable");
            Item chosen = rest.remove(pick);
            out.add(chosen);
            prev = chosen.category();
        }
        return out;
    }

    // After removing rest[i] and placing it, the remainder must be arrangeable with its first
    // element not equal to rest[i].category: the remainder's top category count must be at most
    // ceil(m/2), and if that top category is rest[i]'s own, at most floor(m/2).
    private static boolean canFollow(List<Item> rest, int i) {
        int placedCat = rest.get(i).category();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int k = 0; k < rest.size(); k++) {
            if (k != i) counts.merge(rest.get(k).category(), 1, Integer::sum);
        }
        int m = rest.size() - 1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            int limit = e.getKey() == placedCat ? m / 2 : (m + 1) / 2;
            if (e.getValue() > limit) return false;
        }
        return true;
    }
}
