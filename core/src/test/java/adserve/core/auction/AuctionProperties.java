package adserve.core.auction;

import adserve.core.pod.Arrangement;
import adserve.core.pod.DpSolver;
import adserve.core.pod.ExactSolver;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;
import adserve.core.pod.PodSolver;
import adserve.core.pod.PodValidator;
import adserve.core.pod.Separation;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Second-price pricing on random catalogues, with the server's solver: a price never exceeds the
 * winner's bid, never falls below the reserve, never falls when a rival's score rises, and the
 * rival that set it really could have taken the slot.
 */
class AuctionProperties {

    record Case(List<Item> items, long[] bids, PodRules rules, long reserve, long seed) {}

    @Provide
    Arbitrary<Case> catalogues() {
        Arbitrary<Long> seeds = Arbitraries.longs();
        Arbitrary<Integer> campaigns = Arbitraries.integers().between(1, 30);
        Arbitrary<Integer> advertisers = Arbitraries.integers().between(1, 8);
        Arbitrary<Integer> categories = Arbitraries.integers().between(1, 4);
        Arbitrary<Integer> capacity = Arbitraries.of(15, 30, 60, 90, 120);
        Arbitrary<Separation> sep = Arbitraries.of(Separation.class);
        Arbitrary<Long> reserve = Arbitraries.longs().between(0, 2_000);
        Arbitrary<Boolean> quality = Arbitraries.of(true, false);
        return Combinators.combine(seeds, campaigns, advertisers, categories, capacity, sep, reserve, quality)
                .as((seed, nc, na, ncat, cap, s, res, q) -> {
                    Random r = new Random(seed);
                    List<Item> items = new ArrayList<>();
                    List<Long> bidList = new ArrayList<>();
                    int[] durations = {15, 30, 60};
                    for (int c = 0; c < nc; c++) {
                        int adv = r.nextInt(na);
                        int creatives = 1 + r.nextInt(2);
                        for (int x = 0; x < creatives; x++) {
                            long bid = 1 + r.nextInt(5_000);
                            if (bid < res) continue; // below the reserve: never enters
                            long score = q ? Auction.score(bid, r.nextDouble(), 0.5) : bid;
                            if (score <= 0) continue;
                            items.add(new Item(items.size(), c, adv, adv % ncat, durations[r.nextInt(3)], score));
                            bidList.add(bid);
                        }
                    }
                    long[] bids = bidList.stream().mapToLong(Long::longValue).toArray();
                    return new Case(items, bids, new PodRules(cap, 1, 6, s), res, seed);
                });
    }

    static final PodSolver DP = new DpSolver();

    @Property(tries = 3000)
    void priceIsBetweenTheReserveAndTheBid(@ForAll("catalogues") Case c) {
        Pod pod = DP.solve(c.items(), c.rules());
        AuctionConfig cfg = AuctionConfig.defaults().withReserve(c.reserve());
        Auction.Clearing[] cl = Auction.price(pod, c.items(), c.bids(), c.rules(), cfg);
        for (int s = 0; s < cl.length; s++) {
            Item w = pod.items().get(s);
            assertThat(cl[s].bidMicros()).isEqualTo(c.bids()[w.ref()]);
            assertThat(cl[s].priceMicros()).as("slot %d of %s", s, c).isLessThanOrEqualTo(cl[s].bidMicros());
            assertThat(cl[s].priceMicros()).as("slot %d of %s", s, c).isGreaterThanOrEqualTo(c.reserve());
        }
        Auction.Clearing[] fp = Auction.price(pod, c.items(), c.bids(), c.rules(),
                cfg.withPricing(PricingRule.FIRST_PRICE));
        for (int s = 0; s < fp.length; s++) {
            assertThat(fp[s].priceMicros()).isEqualTo(fp[s].bidMicros()).isGreaterThanOrEqualTo(cl[s].priceMicros());
        }
    }

    @Property(tries = 3000)
    void priceIsMonotoneInEveryRivalsScore(@ForAll("catalogues") Case c) {
        Pod pod = DP.solve(c.items(), c.rules());
        if (pod.size() == 0) return;
        AuctionConfig cfg = AuctionConfig.defaults().withReserve(c.reserve());
        Auction.Clearing[] before = Auction.price(pod, c.items(), c.bids(), c.rules(), cfg);
        Set<Integer> inPod = new HashSet<>();
        for (Item i : pod.items()) inPod.add(i.ref());
        Random r = new Random(c.seed());
        // Raise one excluded candidate's score (and bid alike), keep the pod: no slot's price falls.
        List<Item> excluded = c.items().stream().filter(i -> !inPod.contains(i.ref())).toList();
        if (excluded.isEmpty()) return;
        Item raised = excluded.get(r.nextInt(excluded.size()));
        long bump = 1 + r.nextInt(3_000);
        List<Item> items = new ArrayList<>(c.items());
        items.set(raised.ref(), new Item(raised.ref(), raised.campaign(), raised.advertiser(), raised.category(),
                raised.durationS(), raised.value() + bump));
        long[] bids = c.bids().clone();
        bids[raised.ref()] += bump;
        Auction.Clearing[] after = Auction.price(pod, items, bids, c.rules(), cfg);
        for (int s = 0; s < after.length; s++) {
            assertThat(after[s].priceMicros()).as("slot %d of %s", s, c).isGreaterThanOrEqualTo(before[s].priceMicros());
        }
    }

    @Property(tries = 2000)
    void theRivalThatSetThePriceCouldHaveTakenTheSlot(@ForAll("catalogues") Case c) {
        Pod pod = DP.solve(c.items(), c.rules());
        Auction.Clearing[] cl = Auction.price(pod, c.items(), c.bids(), c.rules(), AuctionConfig.defaults());
        for (int s = 0; s < cl.length; s++) {
            if (cl[s].rivalRef() < 0) continue;
            List<Item> swapped = new ArrayList<>(pod.items());
            swapped.set(s, c.items().get(cl[s].rivalRef()));
            Pod alt = Pod.of(Arrangement.order(swapped, c.rules().separation()));
            assertThat(PodValidator.violation(alt, c.items(), c.rules())).as("slot %d of %s", s, c).isNull();
        }
    }

    @Property(tries = 1500)
    void underTheExactOptimumNoRivalOutscoresTheSlotItWouldTake(@ForAll("catalogues") Case c) {
        if (c.items().size() > 24) return;
        ExactSolver exact = new ExactSolver();
        Pod pod = exact.solve(c.items(), c.rules());
        if (!exact.lastProvedOptimal()) return;
        Auction.Clearing[] cl = Auction.price(pod, c.items(), c.bids(), c.rules(), AuctionConfig.defaults());
        for (int s = 0; s < cl.length; s++) {
            if (cl[s].rivalRef() < 0) continue;
            assertThat(c.items().get(cl[s].rivalRef()).value()).isLessThanOrEqualTo(pod.items().get(s).value());
        }
    }
}
