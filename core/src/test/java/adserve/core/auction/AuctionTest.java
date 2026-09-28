package adserve.core.auction;

import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;
import adserve.core.pod.Separation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuctionTest {
    static final AuctionConfig GSP = AuctionConfig.defaults();

    static Item item(int ref, int adv, int cat, int dur, long value) {
        return new Item(ref, ref, adv, cat, dur, value);
    }

    static long[] bids(List<Item> items) {
        long[] b = new long[items.size()];
        for (Item i : items) b[i.ref()] = i.value();
        return b;
    }

    @Test
    void oneSlotTwoBiddersIsVickrey() {
        List<Item> items = List.of(item(0, 0, 0, 30, 900), item(1, 1, 1, 30, 600));
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(List.of(items.get(0))), items, bids(items), rules, GSP);
        assertThat(c[0].priceMicros()).isEqualTo(600);
        assertThat(c[0].bidMicros()).isEqualTo(900);
        assertThat(c[0].rivalRef()).isEqualTo(1);
    }

    @Test
    void aLoneBidderPaysTheReserve() {
        List<Item> items = List.of(item(0, 0, 0, 30, 900));
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(items), items, bids(items), rules, GSP.withReserve(250));
        assertThat(c[0].priceMicros()).isEqualTo(250);
        assertThat(c[0].rivalRef()).isEqualTo(-1);
    }

    @Test
    void theReserveFloorsALowRival() {
        List<Item> items = List.of(item(0, 0, 0, 30, 900), item(1, 1, 1, 30, 100));
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(List.of(items.get(0))), items, bids(items), rules, GSP.withReserve(250));
        assertThat(c[0].priceMicros()).isEqualTo(250);
        assertThat(c[0].rivalRef()).isEqualTo(-1);
    }

    @Test
    void aRivalThatCannotFitTheSlotDoesNotSetThePrice() {
        // 30 s break, the 60 s rival could never have played.
        List<Item> items = List.of(item(0, 0, 0, 30, 900), item(1, 1, 1, 60, 5_000), item(2, 2, 2, 15, 300));
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(List.of(items.get(0))), items, bids(items), rules, GSP);
        assertThat(c[0].priceMicros()).isEqualTo(300);
        assertThat(c[0].rivalRef()).isEqualTo(2);
    }

    @Test
    void theWinnersOwnOtherCreativeAndOtherSlotsAdvertisersDoNotSetThePrice() {
        // Slot 0 is advertiser 0, slot 1 is advertiser 1. Advertiser 0's other creative and
        // advertiser 1's other creative are excluded from pricing slot 0; advertiser 2 is not.
        List<Item> items = List.of(
                item(0, 0, 0, 30, 900), item(1, 1, 1, 30, 800),
                item(2, 0, 0, 30, 850), item(3, 1, 1, 15, 870), item(4, 2, 2, 30, 400));
        PodRules rules = new PodRules(60, 1, 6, Separation.ADJACENT);
        Pod pod = Pod.of(List.of(items.get(0), items.get(1)));
        Auction.Clearing[] c = Auction.price(pod, items, bids(items), rules, GSP);
        assertThat(c[0].priceMicros()).isEqualTo(400);
        assertThat(c[1].priceMicros()).isEqualTo(400);
    }

    @Test
    void separationLimitsWhoCouldHaveTakenTheSlot() {
        // Pod: auto (cat 0), retail (cat 1), auto (cat 0) in 90 s. Replacing the retail spot with a
        // third auto spot would leave three autos: not arrangeable, so that rival is skipped.
        List<Item> items = List.of(
                item(0, 0, 0, 30, 900), item(1, 1, 1, 30, 500), item(2, 2, 0, 30, 800),
                item(3, 3, 0, 30, 700), item(4, 4, 2, 30, 200));
        PodRules rules = new PodRules(90, 1, 6, Separation.ADJACENT);
        Pod pod = Pod.of(List.of(items.get(0), items.get(1), items.get(2)));
        Auction.Clearing[] c = Auction.price(pod, items, bids(items), rules, GSP);
        assertThat(c[1].rivalRef()).isEqualTo(4);
        assertThat(c[1].priceMicros()).isEqualTo(200);
        assertThat(c[0].rivalRef()).isEqualTo(3);
        assertThat(c[0].priceMicros()).isEqualTo(700);
    }

    @Test
    void firstPriceChargesTheBid() {
        List<Item> items = List.of(item(0, 0, 0, 30, 900), item(1, 1, 1, 30, 600));
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(List.of(items.get(0))), items, bids(items), rules,
                GSP.withPricing(PricingRule.FIRST_PRICE));
        assertThat(c[0].priceMicros()).isEqualTo(900);
    }

    @Test
    void aPenalisedWinnerPaysMoreToBeatTheSameRival() {
        // Bid value 1,000 with 20% skips at weight 0.5 scores 900. Beating a rival that scores 600
        // needs a bid value of 600 / 0.9 = 667.
        long score = Auction.score(1_000, 0.2, 0.5);
        assertThat(score).isEqualTo(900);
        List<Item> items = List.of(item(0, 0, 0, 30, score), item(1, 1, 1, 30, 600));
        long[] b = {1_000, 600};
        PodRules rules = new PodRules(30, 1, 6, Separation.ADJACENT);
        Auction.Clearing[] c = Auction.price(Pod.of(List.of(items.get(0))), items, b, rules, GSP);
        assertThat(c[0].priceMicros()).isEqualTo(667);
    }

    @Test
    void theQualityTermIsOffByDefault() {
        assertThat(AuctionConfig.defaults().qualityWeight()).isZero();
        assertThat(Auction.score(1_000, 0.9, 0)).isEqualTo(1_000);
    }

    @Test
    void criticalValueSeesAPairOfShortSpotsThatTheSwapMisses() {
        // 60 s break. The winner is one 60 s spot scoring 1,000. No single rival fits alone with
        // a high score, but two 30 s spots from different advertisers together score 900: the
        // winner's critical value is 900, while the best single swap is only 500.
        List<Item> items = List.of(item(0, 0, 0, 60, 1_000), item(1, 1, 1, 30, 500), item(2, 2, 2, 30, 400));
        PodRules rules = new PodRules(60, 1, 6, Separation.ADJACENT);
        Pod pod = Pod.of(List.of(items.get(0)));
        assertThat(Auction.price(pod, items, bids(items), rules, GSP)[0].priceMicros()).isEqualTo(500);
        assertThat(Auction.criticalPrices(pod, items, bids(items), rules, new adserve.core.pod.ExactSolver(), GSP)[0])
                .isEqualTo(900);
    }
}
