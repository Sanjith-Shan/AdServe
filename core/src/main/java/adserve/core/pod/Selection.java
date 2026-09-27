package adserve.core.pod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A mutable working set used by the heuristic solvers, with O(1) constraint checks. */
final class Selection {
    final List<Item> items = new ArrayList<>();
    final Set<Integer> advertisers = new HashSet<>();
    final Set<Integer> campaigns = new HashSet<>();
    int duration;
    long value;

    Selection() {}

    Selection(List<Item> start) {
        start.forEach(this::add);
    }

    boolean canAdd(Item i, PodRules r) {
        if (items.size() >= r.maxAds()) return false;
        if (duration + i.durationS() > r.capacityS()) return false;
        if (advertisers.contains(i.advertiser()) || campaigns.contains(i.campaign())) return false;
        items.add(i);
        boolean ok = Arrangement.arrangeable(items, r.separation());
        items.remove(items.size() - 1);
        return ok;
    }

    void add(Item i) {
        items.add(i);
        advertisers.add(i.advertiser());
        campaigns.add(i.campaign());
        duration += i.durationS();
        value += i.value();
    }

    void remove(Item i) {
        items.remove(i);
        advertisers.remove(i.advertiser());
        campaigns.remove(i.campaign());
        duration -= i.durationS();
        value -= i.value();
    }

    boolean contains(Item i) {
        return items.contains(i);
    }

    Pod toPod(PodRules r) {
        if (items.isEmpty() || items.size() < r.minAds()) return Pod.EMPTY;
        return Pod.of(Arrangement.order(items, r.separation()));
    }
}
