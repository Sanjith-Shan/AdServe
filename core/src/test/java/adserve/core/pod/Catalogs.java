package adserve.core.pod;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Random catalogues for the property tests: campaigns own an advertiser and a category, and 1 to 3 creatives. */
final class Catalogs {
    private Catalogs() {}

    record Case(List<Item> items, PodRules rules) {}

    static Arbitrary<Case> cases(int maxCampaigns) {
        Arbitrary<Long> seeds = Arbitraries.longs();
        Arbitrary<Integer> campaigns = Arbitraries.integers().between(0, maxCampaigns);
        Arbitrary<Integer> advertisers = Arbitraries.integers().between(1, 8);
        Arbitrary<Integer> categories = Arbitraries.integers().between(1, 5);
        Arbitrary<Integer> capacity = Arbitraries.of(15, 30, 45, 60, 90, 120, 150, 180);
        Arbitrary<Integer> min = Arbitraries.integers().between(0, 3);
        Arbitrary<Integer> extra = Arbitraries.integers().between(0, 5);
        Arbitrary<Separation> sep = Arbitraries.of(Separation.class);
        return Combinators.combine(seeds, campaigns, advertisers, categories, capacity, min, extra, sep)
                .as((seed, nc, na, ncat, cap, mn, ex, s) -> {
                    Random r = new Random(seed);
                    List<Item> items = new ArrayList<>();
                    int[] durations = {15, 30, 60};
                    for (int c = 0; c < nc; c++) {
                        int adv = r.nextInt(na);
                        int cat = adv % ncat; // an advertiser sits in one category
                        int creatives = 1 + r.nextInt(3);
                        for (int x = 0; x < creatives; x++) {
                            int d = durations[r.nextInt(3)];
                            long v = 1 + r.nextInt(5_000);
                            items.add(new Item(items.size(), c, adv, cat, d, v));
                        }
                    }
                    return new Case(items, new PodRules(cap, mn, mn + ex, s));
                });
    }

    /** Enumerates every subset: the ground truth for small catalogues. */
    static long bruteForce(List<Item> items, PodRules r) {
        int n = items.size();
        long best = 0;
        for (int mask = 1; mask < (1 << n); mask++) {
            int k = Integer.bitCount(mask);
            if (k > r.maxAds() || k < Math.max(1, r.minAds())) continue;
            List<Item> sel = new ArrayList<>();
            int dur = 0;
            long val = 0;
            java.util.Set<Integer> adv = new java.util.HashSet<>();
            java.util.Set<Integer> camp = new java.util.HashSet<>();
            boolean ok = true;
            for (int i = 0; i < n && ok; i++) {
                if ((mask & (1 << i)) == 0) continue;
                Item it = items.get(i);
                ok = adv.add(it.advertiser()) && camp.add(it.campaign());
                dur += it.durationS();
                val += it.value();
                sel.add(it);
            }
            if (!ok || dur > r.capacityS() || !Arrangement.arrangeable(sel, r.separation())) continue;
            best = Math.max(best, val);
        }
        return best;
    }
}
