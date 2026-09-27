package adserve.sim.pods;

import ads.v1.AdRequest;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.pod.DpSolver;
import adserve.core.pod.ExactSolver;
import adserve.core.pod.GreedySolver;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;
import adserve.core.pod.PodSolver;
import adserve.core.pod.PodValidator;
import adserve.core.pod.Separation;
import adserve.core.policy.BrandSafety;
import adserve.core.targeting.RequestContext;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.HdrHistogram.Histogram;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Experiment 4: optimality gap and solve time of each pod solver against the exact branch and
 * bound. Two workloads:
 * <ul>
 *   <li>real breaks: the first N ad breaks of the replay day, with every campaign that passes
 *       targeting, flight and brand safety as a candidate (caps and pacing off, so the pod problem
 *       is as large as the day allows);</li>
 *   <li>stress catalogues: synthetic breaks with 40 to 200 candidates from 30 advertisers in 8
 *       categories, because the real day has only 5 advertisers and a 5-group problem is easy.</li>
 * </ul>
 * Each workload runs once untimed to warm the JIT, then once timed.
 */
public final class PodExperiment {

    record Break(List<Item> items, PodRules rules) {}

    public static void main(String[] a) throws Exception {
        Path campaigns = Path.of(a.length > 0 ? a[0] : "data/work/campaigns.json");
        Path requests = Path.of(a.length > 1 ? a[1] : "data/work/requests-20130611.bin");
        int n = a.length > 2 ? Integer.parseInt(a[2]) : 5000;

        List<Campaign> cs = CampaignFiles.read(campaigns);
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        List<AdRequest> reqs = RequestFiles.readAll(requests, n);
        BrandSafety bs = BrandSafety.defaults();
        for (Separation sep : List.of(Separation.ADJACENT, Separation.POD)) {
            List<Break> real = new ArrayList<>();
            for (AdRequest r : reqs) real.add(new Break(candidates(snap, r, bs), new PodRules(r.getBreakLengthS(), 1, 6, sep)));
            run("real", "iPinYou 2013-06-11, first " + n + " ad breaks, all targeted campaigns as candidates", real, sep);
            run("stress", "synthetic: 40-200 candidates, 30 advertisers, 8 categories, breaks 60-180 s", stress(n, 42, sep), sep);
        }
    }

    static List<Item> candidates(CampaignSnapshot snap, AdRequest r, BrandSafety bs) {
        RequestContext ctx = snap.targeting().context(r.getGeo(), r.getDevice(), r.getSegmentsList(), r.getGenre());
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < snap.size(); i++) {
            Campaign c = snap.campaign(i);
            if (!snap.targeting().matches(i, ctx) || !c.inFlight(r.getTsMs()) || !bs.allowed(c.category(), r.getGenre())) continue;
            for (int x = 0; x < c.creatives().size(); x++) {
                items.add(new Item(items.size(), i, snap.advertiser(i), snap.category(i),
                        c.creatives().get(x).durationS(), snap.creativeValue(i, x)));
            }
        }
        return items;
    }

    static List<Break> stress(int n, long seed, Separation sep) {
        Random rnd = new Random(seed);
        int[] durations = {15, 30, 60};
        int[] breaks = {60, 90, 90, 120, 150, 180};
        List<Break> out = new ArrayList<>();
        for (int b = 0; b < n; b++) {
            int campaigns = 20 + rnd.nextInt(80);
            List<Item> items = new ArrayList<>();
            for (int c = 0; c < campaigns; c++) {
                int adv = rnd.nextInt(30);
                int cat = adv % 8;
                int creatives = 1 + rnd.nextInt(3);
                double base = Math.exp(rnd.nextGaussian() * 0.6) * 1000;
                for (int x = 0; x < creatives; x++) {
                    int d = durations[rnd.nextInt(3)];
                    long v = Math.max(1, Math.round(base * (d == 15 ? 0.6 : d == 30 ? 1.0 : 1.7) * (0.8 + 0.4 * rnd.nextDouble())));
                    items.add(new Item(items.size(), c, adv, cat, d, v));
                }
            }
            out.add(new Break(items, new PodRules(breaks[rnd.nextInt(breaks.length)], 2, 6, sep)));
        }
        return out;
    }

    static void run(String workload, String description, List<Break> breaks, Separation sep) throws Exception {
        List<PodSolver> solvers = List.of(new GreedySolver(false), new GreedySolver(true), new DpSolver());
        ExactSolver exact = new ExactSolver(5_000_000);
        // Warm-up pass.
        for (Break b : breaks) {
            exact.solve(b.items(), b.rules());
            for (PodSolver s : solvers) s.solve(b.items(), b.rules());
        }
        int k = solvers.size();
        Histogram[] time = new Histogram[k + 1];
        for (int i = 0; i <= k; i++) time[i] = new Histogram(3);
        double[] gapSum = new double[k];
        double[] gapMax = new double[k];
        long[] optimalHits = new long[k];
        long[] invalid = new long[k + 1];
        double[] valueSum = new double[k + 1];
        long proved = 0, unproved = 0, candidateSum = 0, nonEmpty = 0;
        double relaxedGapSum = 0;
        for (Break b : breaks) {
            candidateSum += b.items().size();
            long t0 = System.nanoTime();
            Pod best = exact.solve(b.items(), b.rules());
            time[k].recordValue(Math.max(1, (System.nanoTime() - t0) / 1000));
            if (PodValidator.violation(best, b.items(), b.rules()) != null) invalid[k]++;
            if (!exact.lastProvedOptimal()) {
                unproved++;
                continue;
            }
            proved++;
            valueSum[k] += best.value();
            if (best.value() > 0) {
                nonEmpty++;
                long relaxed = DpSolver.relaxedBound(b.items(), b.rules().capacityS());
                relaxedGapSum += 1.0 - (double) best.value() / relaxed;
            }
            for (int i = 0; i < k; i++) {
                long s0 = System.nanoTime();
                Pod p = solvers.get(i).solve(b.items(), b.rules());
                time[i].recordValue(Math.max(1, (System.nanoTime() - s0) / 1000));
                if (PodValidator.violation(p, b.items(), b.rules()) != null) invalid[i]++;
                valueSum[i] += p.value();
                if (best.value() > 0) {
                    double gap = 1.0 - (double) p.value() / best.value();
                    gapSum[i] += gap;
                    gapMax[i] = Math.max(gapMax[i], gap);
                    if (p.value() == best.value()) optimalHits[i]++;
                }
            }
        }
        ObjectNode out = Results.line("exp4_pods");
        out.put("workload", workload);
        out.put("description", description);
        out.put("separation", sep.name().toLowerCase());
        out.put("breaks", breaks.size());
        out.put("breaks_proved_optimal", proved);
        out.put("breaks_exact_hit_node_limit", unproved);
        out.put("mean_candidates", (double) candidateSum / breaks.size());
        out.put("exact_mean_gap_to_capacity_only_relaxation", nonEmpty == 0 ? 0 : relaxedGapSum / nonEmpty);
        ArrayNode rows = out.putArray("solvers");
        for (int i = 0; i <= k; i++) {
            ObjectNode r = rows.addObject();
            r.put("solver", i < k ? solvers.get(i).name() : "exact");
            if (i < k) {
                r.put("mean_gap_pct", nonEmpty == 0 ? 0 : 100.0 * gapSum[i] / nonEmpty);
                r.put("max_gap_pct", 100.0 * gapMax[i]);
                r.put("optimal_share", nonEmpty == 0 ? 1 : (double) optimalHits[i] / nonEmpty);
                r.put("total_value_vs_exact", valueSum[k] == 0 ? 1 : valueSum[i] / valueSum[k]);
            }
            r.put("invalid_pods", invalid[i]);
            r.put("solve_p50_us", time[i].getValueAtPercentile(50));
            r.put("solve_p99_us", time[i].getValueAtPercentile(99));
            r.put("solve_max_us", time[i].getMaxValue());
        }
        Results.append("exp4_pods.jsonl", out);
        System.out.println(out.toPrettyString());
    }
}
