package adserve.core.auction;

import adserve.core.pod.Arrangement;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;

import java.util.ArrayList;
import java.util.List;

/**
 * The auction around pod assembly. Two parts:
 *
 * <ul>
 *   <li><b>Score.</b> A creative's auction score is its bid value, {@code cpc_bid * pCTR *
 *   duration_factor} (the eCPM of one impression, in micros), times an optional quality factor
 *   {@code 1 - weight * skip_rate} that is off by default. The pod solver maximises total score.
 *   <li><b>Pricing.</b> After the pod is assembled, each slot is priced on its own. Under second
 *   price the winner pays the least bid that would still have kept the slot against the best
 *   excluded candidate that could have taken it: {@code rival_score / winner_score * winner_bid_value}
 *   (with the quality term off, simply the rival's score), floored at the reserve and capped at
 *   the winner's own bid value. Under first price the winner pays its bid value.
 * </ul>
 *
 * <p>A rival "could have taken the slot" when swapping it in for the winner leaves a legal pod:
 * it is not already in the pod, its advertiser is neither the winner's nor any other slot's, the
 * pod still fits the break, and the categories can still be ordered under the separation rule.
 * This is an approximation of a position auction: it prices each slot against one swap, not
 * against the best pod without the winner (see DESIGN.md, The auction).
 */
public final class Auction {
    private Auction() {}

    /** Auction score of one impression: bid value, less the optional skip-rate penalty. */
    public static long score(long bidValueMicros, double skipRate, double qualityWeight) {
        if (qualityWeight <= 0 || skipRate <= 0) return bidValueMicros;
        double s = Math.min(1.0, skipRate);
        return Math.round(bidValueMicros * (1.0 - qualityWeight * s));
    }

    /**
     * What one slot cleared at.
     *
     * @param priceMicros what the impression is charged
     * @param bidMicros   the winner's own bid value, which first price would have charged
     * @param scoreMicros the winner's auction score
     * @param rivalRef    {@link Item#ref()} of the candidate that set the price, or -1 when the
     *                    reserve (or, under first price, the winner's own bid) set it
     */
    public record Clearing(long priceMicros, long bidMicros, long scoreMicros, int rivalRef) {}

    /**
     * Prices every slot of {@code pod}. {@code bidValueByRef[item.ref()]} is each candidate's bid
     * value; {@code item.value()} is its score.
     */
    public static Clearing[] price(Pod pod, List<Item> candidates, long[] bidValueByRef, PodRules rules,
                                   AuctionConfig cfg) {
        List<Item> slots = pod.items();
        Clearing[] out = new Clearing[slots.size()];
        int podDuration = pod.durationS();
        for (int s = 0; s < slots.size(); s++) {
            Item w = slots.get(s);
            long bid = bidValueByRef[w.ref()];
            if (cfg.pricing() == PricingRule.FIRST_PRICE) {
                out[s] = new Clearing(bid, bid, w.value(), -1);
                continue;
            }
            Item rival = bestRival(slots, s, candidates, podDuration, rules);
            long price = cfg.reserveMicros();
            int rivalRef = -1;
            if (rival != null) {
                long p = w.value() <= 0 ? bid : (long) Math.ceil((double) rival.value() * bid / w.value());
                if (p > price) {
                    price = p;
                    rivalRef = rival.ref();
                }
            }
            if (price >= bid) price = bid;
            out[s] = new Clearing(price, bid, w.value(), rivalRef);
        }
        return out;
    }

    /** The highest-scoring excluded candidate that could replace slot {@code s} in a legal pod. */
    static Item bestRival(List<Item> slots, int s, List<Item> candidates, int podDuration, PodRules rules) {
        Item w = slots.get(s);
        int room = rules.capacityS() - podDuration + w.durationS();
        Item best = null;
        List<Item> swapped = null;
        outer:
        for (Item c : candidates) {
            if (best != null && c.value() <= best.value()) continue;
            if (c.advertiser() == w.advertiser() || c.durationS() > room) continue;
            for (Item o : slots) {
                if (o.ref() == c.ref() || o.advertiser() == c.advertiser()) continue outer;
            }
            if (swapped == null) {
                swapped = new ArrayList<>(slots);
            }
            swapped.set(s, c);
            if (Arrangement.arrangeable(swapped, rules.separation())) best = c;
        }
        return best;
    }
}
