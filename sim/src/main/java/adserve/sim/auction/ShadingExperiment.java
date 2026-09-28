package adserve.sim.auction;

import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Impression;
import adserve.core.auction.PricingRule;
import adserve.core.engine.DecisionEngine;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Pricing;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Experiment 12: one advertiser shades its bid by 0 to 50% against rivals that bid as before,
 * under second price and first price.
 *
 * <p>Same unconstrained setup as experiment 11 (unlimited budgets, unpaced, caps on in memory):
 * every configuration runs the replayed breaks through two real engines in lockstep, one per
 * pricing rule, and checks the pods are identical. Shading scales every campaign of the one
 * advertiser by {@code 1 - s}; its creatives' auction scores and bid values scale with it. The
 * advertiser's value for an impression it wins is its unshaded bid value (what it bid when
 * truthful), so surplus = unshaded value of the impressions won minus what it was charged.
 */
public final class ShadingExperiment {
    static final int[] SHADES_PCT = {0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50};

    /** One advertiser's outcome under one rule. */
    static final class Outcome {
        long impressions, cost, value, bid, byRival;
    }

    /** Result of one configuration: per rule, per advertiser. */
    record Run(String shader, int shadePct, Map<String, Outcome> gsp, Map<String, Outcome> fp, long gspRevenue,
               long fpRevenue, long decisions, double seconds) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> f = AuctionSim.flags(args);
        Path campaignsFile = Path.of(f.getOrDefault("campaigns", "data/work/campaigns.json"));
        Path dayFile = Path.of(f.getOrDefault("requests", "data/work/requests-20130611.bin"));
        long reserve = AuctionSim.reserve(f);
        boolean capsOn = !"off".equals(f.getOrDefault("caps", "on"));
        int viewerMod = Integer.parseInt(f.getOrDefault("viewer-sample", "1"));
        int threads = Integer.parseInt(f.getOrDefault("threads", "4"));
        String out = f.getOrDefault("out", "exp12_shading.jsonl");
        String day = AuctionSim.replayDay(dayFile);

        List<Campaign> base = AuctionSim.unconstrained(CampaignFiles.read(campaignsFile));
        Map<String, Long> trueValue = new HashMap<>();
        TreeSet<String> advertisers = new TreeSet<>();
        Map<String, Integer> campaignsOf = new HashMap<>();
        for (Campaign c : base) {
            advertisers.add(c.advertiserId());
            campaignsOf.merge(c.advertiserId(), 1, Integer::sum);
            for (CreativeSpec cr : c.creatives()) {
                trueValue.put(c.id() + "/" + cr.id(), Pricing.impressionValueMicros(c.cpcBidMicros(), cr.clickRate(), cr.durationS()));
            }
        }

        // Every break of one viewer in viewerMod (by a hash of the viewer id), so caps still see whole viewers.
        List<AdRequest> reqs = new ArrayList<>();
        long total = RequestFiles.forEach(dayFile, r -> {
            if (viewerMod <= 1 || Math.floorMod(r.getViewerId().hashCode(), viewerMod) == 0) reqs.add(r);
        });
        String traffic = "iPinYou season 2, " + day + ": " + String.format("%,d", reqs.size()) + " ad breaks"
                + (viewerMod > 1 ? String.format(" (every break of 1 viewer in %d by viewer-id hash, of %,d)", viewerMod, total) : "")
                + ", replayed in order";
        System.out.println(traffic);

        List<String[]> configs = new ArrayList<>();
        configs.add(new String[]{"", "0"});
        for (String a : advertisers) for (int s : SHADES_PCT) if (s > 0) configs.add(new String[]{a, Integer.toString(s)});

        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Run>> fs = new ArrayList<>();
        for (String[] c : configs) {
            fs.add(pool.submit(() -> run(base, reqs, c[0], Integer.parseInt(c[1]), reserve, capsOn, trueValue)));
        }
        List<Run> runs = new ArrayList<>();
        for (Future<Run> fu : fs) {
            Run r = fu.get();
            runs.add(r);
            System.out.printf("  %-8s shade %2d%%: %.1f s%n", r.shader().isEmpty() ? "none" : r.shader(), r.shadePct(), r.seconds());
        }
        pool.shutdown();
        double wall = (System.nanoTime() - t0) / 1e9;

        Run baseline = runs.get(0);
        String headline = null;
        for (String a : advertisers) {
            if (headline == null || imps(baseline.gsp(), a) > imps(baseline.gsp(), headline)) headline = a;
        }
        for (String a : advertisers) {
            for (PricingRule rule : PricingRule.values()) {
                Outcome o0 = outcome(baseline, rule, a);
                String best = null;
                long bestSurplus = Long.MIN_VALUE;
                for (Run r : runs) {
                    if (!(r == baseline || r.shader().equals(a))) continue;
                    Outcome o = outcome(r, rule, a);
                    long revenue = rule == PricingRule.SECOND_PRICE ? r.gspRevenue() : r.fpRevenue();
                    ObjectNode n = row(day, traffic, reserve, capsOn, viewerMod, r.decisions());
                    n.put("row", "shade");
                    n.put("advertiser", a);
                    n.put("advertiser_campaigns", campaignsOf.get(a));
                    n.put("headline_advertiser", a.equals(headline));
                    n.put("shade_pct", r.shadePct());
                    n.put("pricing", rule.wire());
                    n.put("impressions", o.impressions);
                    n.put("cost_micros", o.cost);
                    n.put("cost_yuan", AuctionSim.yuan(o.cost));
                    n.put("value_micros", o.value);
                    n.put("value_yuan", AuctionSim.yuan(o.value));
                    n.put("surplus_micros", o.value - o.cost);
                    n.put("surplus_yuan", AuctionSim.yuan(o.value - o.cost));
                    n.put("cost_per_impression_micros", o.impressions == 0 ? 0 : (double) o.cost / o.impressions);
                    n.put("value_per_impression_micros", o.impressions == 0 ? 0 : (double) o.value / o.impressions);
                    n.put("submitted_bid_micros", o.bid);
                    n.put("share_priced_by_rival", o.impressions == 0 ? 0 : (double) o.byRival / o.impressions);
                    n.put("impressions_vs_truthful", ratio(o.impressions, o0.impressions));
                    n.put("cost_per_impression_vs_truthful", o.impressions == 0 || o0.impressions == 0 ? 0
                            : ((double) o.cost / o.impressions) / ((double) o0.cost / o0.impressions));
                    n.put("surplus_change_micros", (o.value - o.cost) - (o0.value - o0.cost));
                    n.put("surplus_change_yuan", AuctionSim.yuan((o.value - o.cost) - (o0.value - o0.cost)));
                    n.put("total_revenue_micros", revenue);
                    n.put("rivals_revenue_micros", revenue - o.cost);
                    n.put("seconds", r.seconds());
                    Results.append(out, n);
                    if (o.value - o.cost > bestSurplus) {
                        bestSurplus = o.value - o.cost;
                        best = Integer.toString(r.shadePct());
                    }
                }
                ObjectNode s = row(day, traffic, reserve, capsOn, viewerMod, baseline.decisions());
                s.put("row", "advertiser_summary");
                s.put("advertiser", a);
                s.put("headline_advertiser", a.equals(headline));
                s.put("pricing", rule.wire());
                s.put("truthful_impressions", o0.impressions);
                s.put("truthful_surplus_micros", o0.value - o0.cost);
                s.put("truthful_surplus_yuan", AuctionSim.yuan(o0.value - o0.cost));
                s.put("surplus_maximising_shade_pct", Integer.parseInt(best));
                s.put("max_surplus_micros", bestSurplus);
                s.put("max_surplus_yuan", AuctionSim.yuan(bestSurplus));
                s.put("max_surplus_gain_over_truthful_micros", bestSurplus - (o0.value - o0.cost));
                s.put("max_surplus_gain_over_truthful_yuan", AuctionSim.yuan(bestSurplus - (o0.value - o0.cost)));
                Results.append(out, s);
                System.out.printf("%-8s %-12s truthful: %,d imps surplus %.2f yuan; best shade %s%% surplus %.2f yuan%n",
                        a, rule.wire(), o0.impressions, AuctionSim.yuan(o0.value - o0.cost), best, AuctionSim.yuan(bestSurplus));
            }
        }
        ObjectNode s = row(day, traffic, reserve, capsOn, viewerMod, baseline.decisions());
        s.put("row", "summary");
        s.put("headline_advertiser", headline);
        s.put("configurations", configs.size());
        s.put("threads", threads);
        s.put("wall_seconds", wall);
        s.put("pods_identical_across_rules", true);
        Results.append(out, s);
        System.out.printf("headline %s; %d configurations in %.1f s%n", headline, configs.size(), wall);
    }

    static Run run(List<Campaign> base, List<AdRequest> reqs, String shader, int shadePct, long reserve, boolean capsOn,
                   Map<String, Long> trueValue) {
        long t0 = System.nanoTime();
        List<Campaign> cs = shader.isEmpty() ? base : AuctionSim.shaded(base, shader, shadePct / 100.0);
        DecisionEngine gsp = AuctionSim.engine(cs, PricingRule.SECOND_PRICE, reserve, capsOn);
        DecisionEngine fp = AuctionSim.engine(cs, PricingRule.FIRST_PRICE, reserve, capsOn);
        Map<String, Outcome> og = new HashMap<>(), of = new HashMap<>();
        long rg = 0, rf = 0, mismatched = 0;
        for (AdRequest req : reqs) {
            AdResponse a = gsp.decide(req).response();
            AdResponse b = fp.decide(req).response();
            if (!AuctionSim.samePod(a, b)) mismatched++;
            rg += tally(og, a, trueValue);
            rf += tally(of, b, trueValue);
        }
        if (mismatched > 0) {
            throw new IllegalStateException(mismatched + " pods differ between rules at " + shader + " shade " + shadePct);
        }
        return new Run(shader, shadePct, og, of, rg, rf, reqs.size(), (System.nanoTime() - t0) / 1e9);
    }

    static long tally(Map<String, Outcome> m, AdResponse resp, Map<String, Long> trueValue) {
        long revenue = 0;
        for (Impression imp : resp.getPodList()) {
            Outcome o = m.computeIfAbsent(imp.getCreative().getAdvertiserId(), k -> new Outcome());
            o.impressions++;
            o.cost += imp.getPriceMicros();
            o.bid += imp.getBidMicros();
            o.value += trueValue.get(imp.getCreative().getCampaignId() + "/" + imp.getCreative().getCreativeId());
            if (!imp.getPricedAgainst().isEmpty()) o.byRival++;
            revenue += imp.getPriceMicros();
        }
        return revenue;
    }

    static Outcome outcome(Run r, PricingRule rule, String advertiser) {
        Outcome o = (rule == PricingRule.SECOND_PRICE ? r.gsp() : r.fp()).get(advertiser);
        return o == null ? new Outcome() : o;
    }

    static long imps(Map<String, Outcome> m, String a) {
        Outcome o = m.get(a);
        return o == null ? 0 : o.impressions;
    }

    static double ratio(long a, long b) {
        return b == 0 ? 0 : (double) a / b;
    }

    static ObjectNode row(String day, String traffic, long reserve, boolean capsOn, int viewerMod, long decisions) {
        ObjectNode o = Results.line("exp12_shading");
        o.put("replay_day", day);
        o.put("traffic", traffic);
        o.put("viewer_sample", viewerMod <= 1 ? "all viewers" : "1 in " + viewerMod + " by viewer-id hash");
        o.put("decisions", decisions);
        o.put("reserve_micros", reserve);
        o.put("budgets", "unlimited");
        o.put("pacing", "off (every campaign unpaced)");
        o.put("frequency_caps", capsOn ? "on, in-memory store per engine (AuctionSim.CompactCapStore)" : "off");
        o.put("rivals", "every other advertiser bids its derived bid, unchanged");
        o.put("value", "the shading advertiser's unshaded bid value of each impression it wins");
        o.put("currency", "micros are millionths of a yuan (CNY), the log's currency");
        return o;
    }
}
