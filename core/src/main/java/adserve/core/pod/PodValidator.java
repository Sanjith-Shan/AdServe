package adserve.core.pod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The invariants no returned pod may break. The decision engine runs this on every pod before it
 * leaves the process and returns an empty pod (and counts the event) if a solver ever produced an
 * invalid one; the property tests prove the solvers never do.
 */
public final class PodValidator {
    private PodValidator() {}

    /** Returns null when the pod is valid, else a description of the first broken rule. */
    public static String violation(Pod pod, List<Item> candidates, PodRules rules) {
        List<Item> items = pod.items();
        if (items.isEmpty()) return null;
        if (items.size() < rules.minAds()) return "fewer than minAds";
        if (items.size() > rules.maxAds()) return "more than maxAds";
        if (pod.durationS() > rules.capacityS()) return "over break length";
        Set<Item> pool = new HashSet<>(candidates);
        Set<Integer> advertisers = new HashSet<>();
        Set<Integer> campaigns = new HashSet<>();
        Set<Integer> categories = new HashSet<>();
        long value = 0;
        Item prev = null;
        for (Item i : items) {
            if (!pool.contains(i)) return "item not among candidates";
            if (!advertisers.add(i.advertiser())) return "advertiser twice";
            if (!campaigns.add(i.campaign())) return "campaign twice";
            boolean newCategory = categories.add(i.category());
            if (rules.separation() == Separation.POD && !newCategory) return "category twice in pod";
            if (rules.separation() == Separation.ADJACENT && prev != null && prev.category() == i.category()) {
                return "same category adjacent";
            }
            value += i.value();
            prev = i;
        }
        if (value != pod.value()) return "value mismatch";
        return null;
    }
}
