package adserve.sim.auction;

import ads.v1.AdResponse;
import ads.v1.Impression;
import adserve.core.auction.Auction;
import adserve.core.auction.AuctionConfig;
import adserve.core.auction.PricingRule;
import adserve.core.engine.DecisionEngine;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.pod.DpSolver;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Experiment 11: what the day clears at under second price against first price, on the same pods.
 *
 * <p>The replay day goes through two real {@link DecisionEngine}s in lockstep, one per pricing
 * rule, each with its own frequency-cap store (in memory, idempotent). Budgets are unlimited and
 * nothing is paced, so the pricing rule cannot change which pods win; every pod is checked to be
 * identical under both rules (creatives, slots, bids and scores), and the run fails if one is
 * not. What differs is only what each slot is charged, read from the logged impressions.
 *
 * <p>Critical value: the second-price engine's pods are also priced at their exact critical
 * values ({@link Auction#criticalPrices}), the least bid that still makes the best pod, which the
 * per-slot GSP approximates; the row reports how far apart they are.
 *
 * <p>Confidence intervals: a paired percentile bootstrap over viewers (every break of a resampled
 * viewer comes with it), for each rule's total and for the second-price / first-price ratio.
 */
public final class PricingExperiment {

    /** Per-rule totals, read from the impressions one engine logged. */
    static final class Tally {
        final PricingRule rule;
        long impressions, price, bid, byRival, byReserve, cappedAtBid, aboveBid, belowReserve;
        double ratioSum;
        final long reserve;
        final Map<String, long[]> byAdvertiser = new TreeMap<>(); // {impressions, price, bid}
        long[] perViewer = new long[1 << 16];

        Tally(PricingRule rule, long reserve) {
            this.rule = rule;
            this.reserve = reserve;
        }

        void add(int viewer, AdResponse resp) {
            if (viewer >= perViewer.length) perViewer = Arrays.copyOf(perViewer, Math.max(viewer + 1, perViewer.length * 2));
            for (Impression imp : resp.getPodList()) {
                long p = imp.getPriceMicros(), b = imp.getBidMicros();
                impressions++;
                price += p;
                bid += b;
                ratioSum += b == 0 ? 1.0 : (double) p / b;
                if (p > b) aboveBid++;
                if (p < reserve) belowReserve++;
                if (!imp.getPricedAgainst().isEmpty()) {
                    byRival++;
                    if (p == b) cappedAtBid++;
                } else if (rule == PricingRule.SECOND_PRICE) {
                    byReserve++;
                }
                perViewer[viewer] += p;
                long[] a = byAdvertiser.computeIfAbsent(imp.getCreative().getAdvertiserId(), k -> new long[3]);
                a[0]++;
                a[1] += p;
                a[2] += b;
            }
        }
    }

    /**
     * Critical-value prices of the second-price engine's pods, against the GSP prices it charged.
     * Items and bids are the ones the engine solved over (captured by {@link AuctionSim.RecordingSolver});
     * with the quality term off and nothing paced, each item's score is its bid value.
     */
    static final class Critical {
        long price, slots, below, equal, above;
        long[] perViewer = new long[1 << 16];
        double[] err = new double[1 << 16];
        int nErr;

        void add(int viewer, AdResponse resp, AuctionSim.RecordingSolver rec, DpSolver solver, AuctionConfig cfg) {
            Pod pod = rec.pod;
            if (pod.size() != resp.getPodCount()) {
                throw new IllegalStateException("captured pod has " + pod.size() + " slots, response " + resp.getPodCount());
            }
            int maxRef = 0;
            for (Item it : rec.items) maxRef = Math.max(maxRef, it.ref());
            long[] bids = new long[maxRef + 1];
            for (Item it : rec.items) bids[it.ref()] = it.value();
            long[] cp = Auction.criticalPrices(pod, rec.items, bids, rec.rules, solver, cfg);
            if (viewer >= perViewer.length) perViewer = Arrays.copyOf(perViewer, Math.max(viewer + 1, perViewer.length * 2));
            for (int s = 0; s < cp.length; s++) {
                Impression imp = resp.getPod(s);
                if (imp.getBidMicros() != pod.items().get(s).value()) {
                    throw new IllegalStateException("slot " + s + ": bid " + imp.getBidMicros() + " is not the captured score "
                            + pod.items().get(s).value() + " (quality term or pacing multiplier on?)");
                }
                long g = imp.getPriceMicros(), c = cp[s];
                price += c;
                perViewer[viewer] += c;
                slots++;
                if (g < c) below++;
                else if (g == c) equal++;
                else above++;
                if (c > 0) {
                    if (nErr == err.length) err = Arrays.copyOf(err, nErr * 2);
                    err[nErr++] = Math.abs(g - c) / (double) c;
                }
            }
        }

        double[] errors() {
            return Arrays.copyOf(err, nErr);
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> f = AuctionSim.flags(args);
        Path campaignsFile = Path.of(f.getOrDefault("campaigns", "data/work/campaigns.json"));
        Path dayFile = Path.of(f.getOrDefault("requests", "data/work/requests-20130611.bin"));
        long reserve = AuctionSim.reserve(f);
        boolean capsOn = !"off".equals(f.getOrDefault("caps", "on"));
        int reps = Integer.parseInt(f.getOrDefault("reps", "1000"));
        long seed = Long.parseLong(f.getOrDefault("seed", "20130611"));
        String out = f.getOrDefault("out", "exp11_pricing.jsonl");
        String day = AuctionSim.replayDay(dayFile);

        List<Campaign> cs = AuctionSim.unconstrained(CampaignFiles.read(campaignsFile));
        AuctionSim.RecordingSolver rec = new AuctionSim.RecordingSolver();
        DecisionEngine gsp = AuctionSim.engine(cs, PricingRule.SECOND_PRICE, reserve, capsOn, rec);
        AuctionConfig critCfg = AuctionConfig.defaults().withReserve(reserve);
        DpSolver critSolver = new DpSolver();
        Critical crit = new Critical();
        DecisionEngine fp = AuctionSim.engine(cs, PricingRule.FIRST_PRICE, reserve, capsOn);
        Tally tg = new Tally(PricingRule.SECOND_PRICE, reserve), tf = new Tally(PricingRule.FIRST_PRICE, reserve);
        Map<String, Integer> viewers = new HashMap<>();
        long[] counts = new long[3]; // decisions, filled, mismatched pods
        long t0 = System.nanoTime();
        RequestFiles.forEach(dayFile, req -> {
            int v = viewers.computeIfAbsent(req.getViewerId(), k -> viewers.size());
            rec.clear();
            AdResponse a = gsp.decide(req).response();
            AdResponse b = fp.decide(req).response();
            if (a.getPodCount() > 0) crit.add(v, a, rec, critSolver, critCfg);
            counts[0]++;
            if (a.getPodCount() > 0) counts[1]++;
            if (!AuctionSim.samePod(a, b)) counts[2]++;
            tg.add(v, a);
            tf.add(v, b);
        });
        double engineSecs = (System.nanoTime() - t0) / 1e9;
        if (counts[2] > 0) {
            throw new IllegalStateException(counts[2] + " of " + counts[0] + " pods differ between the pricing rules; "
                    + "the comparison would not isolate pricing");
        }
        int n = viewers.size();
        long t1 = System.nanoTime();
        Bootstrap.Paired ci = Bootstrap.paired(tg.perViewer, tf.perViewer, n, reps, seed);
        Bootstrap.Paired cc = Bootstrap.paired(tg.perViewer, crit.perViewer, n, reps, seed);
        double bootSecs = (System.nanoTime() - t1) / 1e9;

        String traffic = "iPinYou season 2, " + day + ": " + String.format("%,d", counts[0]) + " ad breaks, replayed in order";
        for (Tally t : List.of(tg, tf)) {
            ObjectNode o = common(day, traffic, cs.size(), reserve, f, capsOn, counts);
            o.put("row", "rule");
            o.put("pricing", t.rule.wire());
            o.put("impressions", t.impressions);
            o.put("revenue_micros", t.price);
            o.put("revenue_yuan", AuctionSim.yuan(t.price));
            boolean second = t.rule == PricingRule.SECOND_PRICE;
            double lo = second ? ci.aLo() : ci.bLo(), hi = second ? ci.aHi() : ci.bHi();
            o.putArray("revenue_ci95_micros").add(Math.round(lo)).add(Math.round(hi));
            o.putArray("revenue_ci95_yuan").add(AuctionSim.yuan(lo)).add(AuctionSim.yuan(hi));
            o.put("bid_value_micros", t.bid);
            o.put("bid_value_yuan", AuctionSim.yuan(t.bid));
            o.put("mean_price_micros", t.impressions == 0 ? 0 : (double) t.price / t.impressions);
            o.put("mean_bid_micros", t.impressions == 0 ? 0 : (double) t.bid / t.impressions);
            o.put("price_to_bid_sum_ratio", t.bid == 0 ? 0 : (double) t.price / t.bid);
            o.put("price_to_bid_mean_ratio", t.impressions == 0 ? 0 : t.ratioSum / t.impressions);
            o.put("slots_price_above_bid", t.aboveBid);
            o.put("slots_price_below_reserve", t.belowReserve);
            if (second) {
                o.put("slots_priced_by_rival", t.byRival);
                o.put("slots_priced_by_reserve", t.byReserve);
                o.put("slots_capped_at_own_bid", t.cappedAtBid);
                o.put("share_priced_by_rival", share(t.byRival, t.impressions));
                o.put("share_priced_by_reserve", share(t.byReserve, t.impressions));
                o.put("share_capped_at_own_bid", share(t.cappedAtBid, t.impressions));
            } else {
                o.put("slots_priced_at_own_bid", t.impressions);
                o.put("share_priced_at_own_bid", 1.0);
            }
            ObjectNode adv = o.putObject("per_advertiser");
            for (var e : t.byAdvertiser.entrySet()) {
                ObjectNode x = adv.putObject(e.getKey());
                long[] a = e.getValue();
                x.put("impressions", a[0]);
                x.put("revenue_micros", a[1]);
                x.put("revenue_yuan", AuctionSim.yuan(a[1]));
                x.put("share_of_revenue", share(a[1], t.price));
                x.put("price_to_bid_sum_ratio", a[2] == 0 ? 0 : (double) a[1] / a[2]);
            }
            Results.append(out, o);
            System.out.printf("%-12s impressions=%,d revenue=%,d micros (%.2f yuan) CI95 [%.2f, %.2f] yuan  price/bid=%.4f%n",
                    t.rule.wire(), t.impressions, t.price, AuctionSim.yuan(t.price), AuctionSim.yuan(lo), AuctionSim.yuan(hi),
                    t.bid == 0 ? 0 : (double) t.price / t.bid);
        }
        ObjectNode s = common(day, traffic, cs.size(), reserve, f, capsOn, counts);
        s.put("row", "summary");
        s.put("second_price_revenue_micros", tg.price);
        s.put("first_price_revenue_micros", tf.price);
        s.put("second_price_revenue_yuan", AuctionSim.yuan(tg.price));
        s.put("first_price_revenue_yuan", AuctionSim.yuan(tf.price));
        s.put("second_over_first", tf.price == 0 ? 0 : (double) tg.price / tf.price);
        s.putArray("second_over_first_ci95").add(ci.ratioLo()).add(ci.ratioHi());
        s.put("first_minus_second_micros", tf.price - tg.price);
        ObjectNode cv = s.putObject("critical_value");
        cv.put("what", "exact least bid that still makes the best pod, per winner: one extra DP solve with the winner's "
                + "advertiser removed (Auction.criticalPrices), same reserve and cap at own bid; items and bids are the "
                + "engine's own, captured from its solver call");
        cv.put("revenue_micros", crit.price);
        cv.put("revenue_yuan", AuctionSim.yuan(crit.price));
        cv.putArray("revenue_ci95_micros").add(Math.round(cc.bLo())).add(Math.round(cc.bHi()));
        cv.putArray("revenue_ci95_yuan").add(AuctionSim.yuan(cc.bLo())).add(AuctionSim.yuan(cc.bHi()));
        cv.put("second_price_over_critical", crit.price == 0 ? 0 : (double) tg.price / crit.price);
        cv.putArray("second_price_over_critical_ci95").add(cc.ratioLo()).add(cc.ratioHi());
        cv.put("slots", crit.slots);
        cv.put("slots_gsp_below_critical", crit.below);
        cv.put("slots_gsp_equal_critical", crit.equal);
        cv.put("slots_gsp_above_critical", crit.above);
        cv.put("share_gsp_below_critical", share(crit.below, crit.slots));
        cv.put("share_gsp_equal_critical", share(crit.equal, crit.slots));
        cv.put("share_gsp_above_critical", share(crit.above, crit.slots));
        double[] err = crit.errors();
        cv.put("error_slots_with_critical_above_0", err.length);
        cv.put("abs_rel_error_mean", Arrays.stream(err).average().orElse(0));
        cv.put("abs_rel_error_p50", Bootstrap.percentile(err, 0.50));
        cv.put("abs_rel_error_p90", Bootstrap.percentile(err, 0.90));
        cv.put("abs_rel_error_p99", Bootstrap.percentile(err, 0.99));
        cv.put("seconds_included_in_engine_seconds", true);
        s.put("first_minus_second_yuan", AuctionSim.yuan(tf.price - tg.price));
        ObjectNode b = s.putObject("bootstrap");
        b.put("method", "paired percentile bootstrap, viewers resampled with replacement, every break of a drawn viewer included");
        b.put("reps", reps);
        b.put("seed", seed);
        b.put("viewers", n);
        b.put("seconds", bootSecs);
        s.put("engine_seconds", engineSecs);
        Results.append(out, s);
        System.out.printf("critical     revenue=%,d micros (%.2f yuan) CI95 [%.2f, %.2f] yuan; gsp/critical=%.4f CI95 [%.4f, %.4f]; "
                        + "gsp <,=,> critical: %.4f %.4f %.4f; |err| mean %.4f p50 %.4f p90 %.4f p99 %.4f%n",
                crit.price, AuctionSim.yuan(crit.price), AuctionSim.yuan(cc.bLo()), AuctionSim.yuan(cc.bHi()),
                (double) tg.price / crit.price, cc.ratioLo(), cc.ratioHi(), share(crit.below, crit.slots),
                share(crit.equal, crit.slots), share(crit.above, crit.slots), cv.get("abs_rel_error_mean").asDouble(),
                cv.get("abs_rel_error_p50").asDouble(), cv.get("abs_rel_error_p90").asDouble(), cv.get("abs_rel_error_p99").asDouble());
        System.out.printf("second/first = %.4f CI95 [%.4f, %.4f]; %,d viewers, %d reps; engines %.1f s, bootstrap %.1f s; pods identical: %,d of %,d%n",
                (double) tg.price / tf.price, ci.ratioLo(), ci.ratioHi(), n, reps, engineSecs, bootSecs, counts[0] - counts[2], counts[0]);
    }

    static ObjectNode common(String day, String traffic, int campaigns, long reserve, Map<String, String> f, boolean capsOn,
                             long[] counts) {
        ObjectNode o = Results.line("exp11_pricing");
        o.put("replay_day", day);
        o.put("traffic", traffic);
        o.put("campaigns", campaigns);
        o.put("reserve_micros", reserve);
        o.put("reserve_source", AuctionSim.reserveSource(f));
        o.put("budgets", "unlimited");
        o.put("pacing", "off (every campaign unpaced)");
        o.put("frequency_caps", capsOn ? "on, in-memory store per engine (AuctionSim.CompactCapStore)" : "off");
        o.put("pods", "real engine (targeting, brand safety, caps, DP pod assembly), run once per rule in lockstep");
        o.put("decisions", counts[0]);
        o.put("breaks_filled", counts[1]);
        o.put("pods_identical_across_rules", counts[2] == 0);
        o.put("pods_mismatched_across_rules", counts[2]);
        o.put("currency", "micros are millionths of a yuan (CNY), the log's currency");
        return o;
    }

    static double share(long x, long of) {
        return of == 0 ? 0 : (double) x / of;
    }
}
