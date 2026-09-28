package adserve.sim.auction;

import ads.v1.AdResponse;
import ads.v1.Impression;
import adserve.core.auction.AuctionConfig;
import adserve.core.caps.CapCounts;
import adserve.core.caps.CapStore;
import adserve.core.caps.InMemoryCapStore;
import adserve.core.caps.Windows;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.DecisionEngine;
import adserve.core.engine.DecisionLog;
import adserve.core.engine.EngineConfig;
import adserve.core.engine.PacingController;
import adserve.core.engine.SnapshotSource;
import adserve.core.engine.StageTimer;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.PacerKind;
import adserve.core.model.Pricing;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.DpSolver;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;
import adserve.sim.Results;
import adserve.sim.auction.Miscalibration.Condition;
import adserve.sim.auction.ViewerBootstrap.Interval;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Experiment 16: what miscalibrated click-rate predictions do to the auction.
 *
 * <p>Each creative's smoothed click rate from the logs is the TRUE rate. The auction ranks and
 * prices with a PREDICTED rate: campaign copies whose creatives carry the distorted rate, so the
 * engine's score ({@code cpc_bid * predicted * duration_factor}) and second price (the rival's
 * score, which uses the rival's predicted rate) both see the distortion. Outcomes are scored with
 * the true rate. Conditions: the truth; every prediction scaled by one factor; one advertiser at a
 * time scaled; mean-one log-normal noise per creative over several seeds.
 *
 * <p>Leg {@code unpaced} (default): unlimited budgets, no pacer, so only the allocation and the
 * prices move. Leg {@code paced}: the file's budgets with the Smart pacer, planned from the
 * previous day's forecast, so budgets bind and price changes buy fewer impressions.
 */
public final class CalibrationExperiment {
    static final int RESAMPLES = 2000;
    static final long BOOT_SEED = 16L;

    public static void main(String[] a) throws Exception {
        Map<String, String> f = AuctionSim.flags(a);
        Path campaignsFile = Path.of(f.getOrDefault("campaigns", "data/work/campaigns.json"));
        Path dayFile = Path.of(f.getOrDefault("requests", "data/work/requests-20130611.bin"));
        Path forecastFile = Path.of(f.getOrDefault("forecast", "data/work/forecast.json"));
        String leg = f.getOrDefault("leg", "unpaced");
        int threads = Integer.parseInt(f.getOrDefault("threads", "3"));
        int seeds = Integer.parseInt(f.getOrDefault("seeds", "10"));
        String out = f.getOrDefault("out", "exp16_calibration.jsonl");
        String capsMode = f.getOrDefault("caps", "replay");
        if (!capsMode.equals("replay") && !capsMode.equals("map")) throw new IllegalArgumentException("--caps replay|map");
        long reserve = AuctionSim.reserve(f);
        String replayDay = AuctionSim.replayDay(dayFile);
        if (!leg.equals("unpaced") && !leg.equals("paced")) throw new IllegalArgumentException("--leg unpaced|paced");
        boolean paced = leg.equals("paced");

        List<Campaign> base = CampaignFiles.read(campaignsFile);
        Map<String, Double> truth = Miscalibration.truth(base);
        List<String> advertisers = Miscalibration.advertisers(base);
        Map<String, double[]> forecast = paced
                ? CampaignFiles.mapper().readValue(forecastFile.toFile(), new TypeReference<>() {}) : Map.of();

        List<Condition> conds = new ArrayList<>();
        conds.add(Condition.baseline());
        // The same truth again: the engine is deterministic, so every change against it must be exactly 0.
        conds.add(new Condition("baseline_repeat", Miscalibration.Kind.BASELINE, 1.0, null, 0, 0));
        for (double u : new double[]{0.5, 0.8, 1.25, 2.0}) conds.add(Condition.uniform(u));
        for (String adv : advertisers) {
            conds.add(Condition.advertiser(adv, 2.0));
            if (!paced) conds.add(Condition.advertiser(adv, 0.5));
        }
        if (!paced) {
            for (double s : new double[]{0.1, 0.25, 0.5}) {
                for (long seed = 1; seed <= seeds; seed++) conds.add(Condition.noise(s, seed));
            }
        }
        // --only REGEX keeps the matching conditions (the baseline always runs: every delta is against it).
        if (f.containsKey("only")) {
            java.util.regex.Pattern only = java.util.regex.Pattern.compile(f.get("only"));
            conds.removeIf(c -> !c.name().equals("baseline") && !only.matcher(c.name()).matches());
        }

        // Per-creative facts the tally needs: advertiser, cpc bid, duration factor, true rate.
        Map<String, Integer> advIndex = Miscalibration.index(advertisers);
        Map<String, double[]> creative = new HashMap<>();
        for (Campaign c : base) {
            for (CreativeSpec s : c.creatives()) {
                creative.put(s.id(), new double[]{advIndex.get(c.advertiserId()), c.cpcBidMicros(),
                        Pricing.durationFactor(s.durationS()), truth.get(s.id())});
            }
        }

        ReplayViewers viewers = capsMode.equals("replay") ? ReplayViewers.scan(dayFile) : null;
        Map<String, Integer> campaignIndex = new HashMap<>();
        for (Campaign c : base) campaignIndex.put(c.id(), campaignIndex.size());
        System.err.printf("exp16: %d conditions, leg %s, reserve %d micros, %s, %d threads, caps %s%s%n",
                conds.size(), leg, reserve, dayFile, threads, capsMode,
                viewers == null ? "" : " (" + viewers.index.size() + " viewers with more than one break)");
        long t0 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Tally>> futures = new ArrayList<>();
        for (Condition cond : conds) {
            futures.add(pool.submit(() -> {
                long s0 = System.nanoTime();
                Map<String, Double> pred = Miscalibration.predicted(base, cond);
                List<Campaign> cs = Miscalibration.withPredictions(paced ? smart(base) : AuctionSim.unconstrained(base), pred);
                CapStore capStore = viewers == null ? new InMemoryCapStore(false)
                        : new ReplayCapStore(viewers, campaignIndex);
                DecisionEngine engine = engine(cs, reserve, paced, forecast, capStore);
                Tally t = new Tally(cond, advertisers.size());
                RequestFiles.forEach(dayFile, req -> {
                    AdResponse resp = engine.decide(req).response();
                    t.requests++;
                    if (resp.getPodCount() == 0) return;
                    int k = ViewerBootstrap.cluster(req.getViewerId());
                    for (Impression imp : resp.getPodList()) {
                        String id = imp.getCreative().getCreativeId();
                        t.add(k, creative.get(id), pred.get(id), imp.getPriceMicros());
                        if (imp.getPricedAgainst().isEmpty()) t.reserveSet++;
                    }
                });
                t.wallSeconds = (System.nanoTime() - s0) / 1e9;
                System.err.printf("  %-24s %6.1f s  imps %d%n", cond.name(), t.wallSeconds, t.impressions);
                return t;
            }));
        }
        List<Tally> tallies = new ArrayList<>();
        for (Future<Tally> fu : futures) tallies.add(fu.get());
        pool.shutdown();
        double total = (System.nanoTime() - t0) / 1e9;

        Tally b = tallies.get(0);
        for (Tally t : tallies) {
            ObjectNode row = row(t, b, advertisers, leg, replayDay, dayFile, reserve, f, total, threads);
            Results.append(out, row);
            System.out.printf("%-26s clicks %+7.2f%% [%+.2f, %+.2f]  cpc %+7.2f%% [%+.2f, %+.2f]  eff %.4f  per-click-billed cpc %+7.2f%%%n",
                    t.cond.name(), row.get("delta_true_clicks_pct").asDouble(),
                    row.get("delta_true_clicks_ci95").get(0).asDouble(), row.get("delta_true_clicks_ci95").get(1).asDouble(),
                    row.get("delta_cost_per_true_click_pct").asDouble(),
                    row.get("delta_cost_per_true_click_ci95").get(0).asDouble(),
                    row.get("delta_cost_per_true_click_ci95").get(1).asDouble(),
                    row.get("allocative_efficiency").asDouble(),
                    row.get("per_click_billing").get("delta_cost_per_true_click_pct").asDouble());
        }
        noiseSummaries(tallies, b, leg, replayDay, dayFile, reserve, f, out);
        System.err.printf("exp16 done in %.0f s%n", total);
    }

    /** The file's budgets under the Smart pacer. */
    static List<Campaign> smart(List<Campaign> cs) {
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), c.dailyBudgetMicros(),
                    c.flightStartMs(), c.flightEndMs(), PacerKind.SMART, c.cap(), c.targeting(), c.creatives(), c.active()));
        }
        return out;
    }

    static DecisionEngine engine(List<Campaign> cs, long reserve, boolean paced, Map<String, double[]> forecast,
                                 CapStore capStore) {
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        BudgetLedger ledger = new BudgetLedger();
        PacingController pacing = PacingController.standard(60_000L, c -> {
            double[] w = paced ? forecast.getOrDefault(c.id(), forecast.get("*")) : null;
            return w == null || w.length != 1440 ? PacingPlan.flat(1440) : new PacingPlan(w, 86_400_000L);
        }, ledger);
        EngineConfig cfg = EngineConfig.defaults().withLogCandidates(false)
                .withAuction(AuctionConfig.defaults().withReserve(reserve));
        TokenCodec tokens = new TokenCodec("calibration-experiment-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        SplittableRandom rnd = new SplittableRandom(20130611L);
        return new DecisionEngine(cfg, SnapshotSource.fixed(snap), capStore, ledger, pacing,
                new DpSolver(), tokens, DecisionLog.NONE, BrandSafety.defaults(), v -> List.of(), rnd::nextDouble,
                StageTimer.NONE);
    }

    /**
     * The replay's viewers that ask for more than one break, each with a dense index, and the one
     * UTC day the file covers. Scanned once and shared read-only by every condition.
     */
    record ReplayViewers(Map<String, Integer> index, long day) {
        static ReplayViewers scan(Path dayFile) throws Exception {
            Map<String, Integer> seen = new HashMap<>();
            long[] days = {Long.MAX_VALUE, Long.MIN_VALUE};
            RequestFiles.forEach(dayFile, req -> {
                seen.merge(req.getViewerId(), 1, Integer::sum);
                long d = Windows.day(req.getTsMs());
                days[0] = Math.min(days[0], d);
                days[1] = Math.max(days[1], d);
            });
            if (days[0] != days[1] || Windows.week(days[0] * Windows.DAY_MS) != Windows.week(days[1] * Windows.DAY_MS)) {
                throw new IllegalStateException("the replay spans more than one UTC day; use --caps map");
            }
            Map<String, Integer> index = new HashMap<>();
            for (Map.Entry<String, Integer> e : seen.entrySet()) if (e.getValue() > 1) index.put(e.getKey(), index.size());
            return new ReplayViewers(index, days[0]);
        }
    }

    /**
     * The same counts as {@link InMemoryCapStore} for a replay of one UTC day, in flat arrays. A
     * viewer seen once in the file is never fetched after its only pod is recorded, so only repeat
     * viewers are counted; within one day (and so one week) the week counter equals the day
     * counter. About 150 bytes per repeat viewer instead of a map entry per impression, which is
     * what lets several conditions share one 4 GB heap. {@code --caps map} runs the map store, and
     * CalibrationHelpersTest checks the two agree.
     */
    static final class ReplayCapStore implements CapStore {
        private final ReplayViewers viewers;
        private final Map<String, Integer> campaigns;
        private final int nc;
        private final int[] dayCount, hourCount;

        ReplayCapStore(ReplayViewers viewers, Map<String, Integer> campaigns) {
            this.viewers = viewers;
            this.campaigns = campaigns;
            this.nc = campaigns.size();
            dayCount = new int[viewers.index().size() * nc];
            hourCount = new int[viewers.index().size() * 24];
        }

        private int hourOfDay(long tsMs) {
            if (Windows.day(tsMs) != viewers.day()) throw new IllegalStateException("request outside the replay day");
            return (int) (Windows.hour(tsMs) - viewers.day() * 24);
        }

        @Override
        public CapCounts fetch(String viewerId, List<String> campaignIds, long nowMs) {
            int n = campaignIds.size();
            long[] day = new long[n];
            int h = hourOfDay(nowMs);
            Integer v = viewers.index().get(viewerId);
            if (v == null) return new CapCounts(day, new long[n], 0, true);
            for (int i = 0; i < n; i++) day[i] = dayCount[v * nc + campaigns.get(campaignIds.get(i))];
            return new CapCounts(day, day.clone(), hourCount[v * 24 + h], true);
        }

        @Override
        public void recordImpression(String viewerId, String campaignId, String eventId, long tsMs) {
            int h = hourOfDay(tsMs);
            Integer v = viewers.index().get(viewerId);
            if (v == null) return;
            dayCount[v * nc + campaigns.get(campaignId)]++;
            hourCount[v * 24 + h]++;
        }
    }

    /** Everything one condition delivered, per viewer cluster and per advertiser. */
    static final class Tally {
        final Condition cond;
        final int k = ViewerBootstrap.CLUSTERS;
        final double[] clicks = new double[k], cost = new double[k], perClickCost = new double[k], value = new double[k];
        final double[][] advClicks, advCost, advPerClickCost;
        final long[] advImps;
        long requests, impressions, overpaid, reserveSet;
        double predictedClicks, wallSeconds;

        Tally(Condition cond, int advertisers) {
            this.cond = cond;
            advClicks = new double[advertisers][k];
            advCost = new double[advertisers][k];
            advPerClickCost = new double[advertisers][k];
            advImps = new long[advertisers];
        }

        /** {@code c} is {advertiser, cpc bid, duration factor, true rate}. */
        void add(int cluster, double[] c, double predicted, long price) {
            int adv = (int) c[0];
            double p = c[3];
            double trueValue = c[1] * p * c[2];
            double billed = Miscalibration.perClickBilled(price, predicted, p);
            impressions++;
            advImps[adv]++;
            predictedClicks += predicted;
            if (price > trueValue) overpaid++;
            clicks[cluster] += p;
            cost[cluster] += price;
            perClickCost[cluster] += billed;
            value[cluster] += trueValue;
            advClicks[adv][cluster] += p;
            advCost[adv][cluster] += price;
            advPerClickCost[adv][cluster] += billed;
        }
    }

    static ObjectNode row(Tally t, Tally b, List<String> advertisers, String leg, String replayDay, Path dayFile,
                          long reserve, Map<String, String> f, double totalSecs, int threads) {
        ObjectNode r = Results.line("exp16_calibration");
        r.put("replay_day", replayDay);
        r.put("replay_file", dayFile.toString());
        r.put("leg", leg);
        r.put("setup", leg.equals("paced")
                ? "file budgets, Smart pacer planned from the 2013-06-10 forecast; second price per pod slot; caps on"
                : "unlimited budgets, unpaced; second price per pod slot; caps on");
        r.put("cap_store", f.getOrDefault("caps", "replay").equals("replay")
                ? "replay arrays (same counts as the in-memory map store for a one-day replay)" : "in-memory map");
        r.put("truth", "each creative's smoothed click rate from the logs; the auction ranks and prices with the predicted rate");
        r.put("reserve_micros", reserve);
        r.put("reserve_source", AuctionSim.reserveSource(f));
        r.put("condition", t.cond.name());
        r.put("kind", t.cond.kind().name().toLowerCase());
        if (t.cond.kind() == Miscalibration.Kind.UNIFORM || t.cond.kind() == Miscalibration.Kind.ADVERTISER) {
            r.put("factor", t.cond.factor());
        }
        if (t.cond.advertiser() != null) r.put("biased_advertiser", t.cond.advertiser());
        if (t.cond.kind() == Miscalibration.Kind.NOISE) {
            r.put("sigma", t.cond.sigma());
            r.put("noise_seed", t.cond.seed());
            r.put("noise", "mean-one log-normal per creative, exp(sigma Z - sigma^2/2)");
        }
        r.put("requests", t.requests);
        r.put("impressions", t.impressions);
        double clicks = ViewerBootstrap.sum(t.clicks), cost = ViewerBootstrap.sum(t.cost);
        double pc = ViewerBootstrap.sum(t.perClickCost), value = ViewerBootstrap.sum(t.value);
        r.put("expected_true_clicks", clicks);
        r.put("predicted_clicks", t.predictedClicks);
        r.put("cleared_revenue_micros", Math.round(cost));
        r.put("cost_per_true_click_micros", cost / clicks);
        r.put("realized_true_value_micros", Math.round(value));
        r.put("allocative_efficiency", value / ViewerBootstrap.sum(b.value));
        r.put("impressions_charged_above_true_value_share", (double) t.overpaid / t.impressions);
        r.put("impressions_priced_by_reserve_share", (double) t.reserveSet / t.impressions);
        r.put("delta_impressions_pct", 100.0 * (t.impressions - b.impressions) / b.impressions);
        r.put("delta_revenue_pct", 100.0 * (cost / ViewerBootstrap.sum(b.cost) - 1));
        long seed = BOOT_SEED + t.cond.name().hashCode();
        putCi(r, "delta_true_clicks", ViewerBootstrap.relativeChange(t.clicks, null, b.clicks, null, RESAMPLES, seed));
        putCi(r, "delta_cost_per_true_click",
                ViewerBootstrap.relativeChange(t.cost, t.clicks, b.cost, b.clicks, RESAMPLES, seed));
        putCi(r, "delta_efficiency", ViewerBootstrap.relativeChange(t.value, null, b.value, null, RESAMPLES, seed));

        ObjectNode pcb = r.putObject("per_click_billing");
        pcb.put("rule", "each impression billed price / predicted_rate * true_rate (the cleared CPC per expected true click)");
        pcb.put("revenue_micros", Math.round(pc));
        pcb.put("cost_per_true_click_micros", pc / clicks);
        pcb.put("delta_revenue_pct", 100.0 * (pc / ViewerBootstrap.sum(b.perClickCost) - 1));
        putCi(pcb, "delta_cost_per_true_click",
                ViewerBootstrap.relativeChange(t.perClickCost, t.clicks, b.perClickCost, b.clicks, RESAMPLES, seed));

        ObjectNode per = r.putObject("per_advertiser");
        for (int i = 0; i < advertisers.size(); i++) {
            ObjectNode o = per.putObject(advertisers.get(i));
            double ac = ViewerBootstrap.sum(t.advClicks[i]), ak = ViewerBootstrap.sum(t.advCost[i]);
            double bc = ViewerBootstrap.sum(b.advClicks[i]), bk = ViewerBootstrap.sum(b.advCost[i]);
            o.put("impressions", t.advImps[i]);
            o.put("impression_share", (double) t.advImps[i] / t.impressions);
            o.put("baseline_impression_share", (double) b.advImps[i] / b.impressions);
            o.put("true_click_share", ac / clicks);
            o.put("baseline_true_click_share", bc / ViewerBootstrap.sum(b.clicks));
            o.put("spend_share", ak / cost);
            o.put("expected_true_clicks", ac);
            o.put("cost_per_true_click_micros", ac > 0 ? ak / ac : Double.NaN);
            o.put("delta_true_clicks_pct", 100 * (ac / bc - 1));
            o.put("delta_cost_per_true_click_pct", 100 * ((ak / ac) / (bk / bc) - 1));
            o.put("per_click_billed_cost_per_true_click_micros", ac > 0 ? ViewerBootstrap.sum(t.advPerClickCost[i]) / ac : Double.NaN);
            if (advertisers.get(i).equals(t.cond.advertiser())) {
                putCi(o, "delta_true_clicks",
                        ViewerBootstrap.relativeChange(t.advClicks[i], null, b.advClicks[i], null, RESAMPLES, seed));
                putCi(o, "delta_cost_per_true_click",
                        ViewerBootstrap.relativeChange(t.advCost[i], t.advClicks[i], b.advCost[i], b.advClicks[i], RESAMPLES, seed));
                putCi(o, "per_click_billing_delta_cost_per_true_click", ViewerBootstrap.relativeChange(
                        t.advPerClickCost[i], t.advClicks[i], b.advPerClickCost[i], b.advClicks[i], RESAMPLES, seed));
            }
        }
        ObjectNode boot = r.putObject("bootstrap");
        boot.put("unit", "viewers, resampled as " + ViewerBootstrap.CLUSTERS + " hash clusters of whole viewers, paired with the baseline");
        boot.put("resamples", RESAMPLES);
        boot.put("seed", seed);
        boot.put("interval", "95% percentile");
        r.put("condition_wall_seconds", t.wallSeconds);
        r.put("experiment_wall_seconds", totalSecs);
        r.put("parallel_conditions", threads);
        return r;
    }

    /**
     * One row per noise level: the spread over seeds. With 55 creatives, one seed is one particular
     * pattern of relative errors, so the seed-to-seed spread is the uncertainty that matters here,
     * far wider than any one seed's viewer bootstrap.
     */
    static void noiseSummaries(List<Tally> tallies, Tally b, String leg, String replayDay, Path dayFile, long reserve,
                               Map<String, String> f, String out) throws Exception {
        Map<Double, List<Tally>> bySigma = new java.util.TreeMap<>();
        for (Tally t : tallies) {
            if (t.cond.kind() == Miscalibration.Kind.NOISE) bySigma.computeIfAbsent(t.cond.sigma(), s -> new ArrayList<>()).add(t);
        }
        double bClicks = ViewerBootstrap.sum(b.clicks), bCost = ViewerBootstrap.sum(b.cost);
        double bValue = ViewerBootstrap.sum(b.value), bPc = ViewerBootstrap.sum(b.perClickCost);
        for (Map.Entry<Double, List<Tally>> e : bySigma.entrySet()) {
            List<Tally> ts = e.getValue();
            int n = ts.size();
            double[][] m = new double[4][n];
            for (int i = 0; i < n; i++) {
                Tally t = ts.get(i);
                double c = ViewerBootstrap.sum(t.clicks);
                m[0][i] = 100 * (c / bClicks - 1);
                m[1][i] = 100 * ((ViewerBootstrap.sum(t.cost) / c) / (bCost / bClicks) - 1);
                m[2][i] = ViewerBootstrap.sum(t.value) / bValue;
                m[3][i] = 100 * ((ViewerBootstrap.sum(t.perClickCost) / c) / (bPc / bClicks) - 1);
            }
            ObjectNode r = Results.line("exp16_calibration_noise_summary");
            r.put("replay_day", replayDay);
            r.put("replay_file", dayFile.toString());
            r.put("leg", leg);
            r.put("reserve_micros", reserve);
            r.put("reserve_source", AuctionSim.reserveSource(f));
            r.put("condition", "noise_s" + e.getKey() + "_over_seeds");
            r.put("sigma", e.getKey());
            r.put("seeds", n);
            r.put("interval", "mean over seeds with a t interval over seeds (df = seeds - 1)");
            String[] names = {"delta_true_clicks_pct", "delta_cost_per_true_click_pct", "allocative_efficiency",
                    "per_click_billing_delta_cost_per_true_click_pct"};
            for (int j = 0; j < 4; j++) {
                ObjectNode o = r.putObject(names[j]);
                double mean = java.util.Arrays.stream(m[j]).average().orElse(Double.NaN);
                double sd = n > 1 ? Math.sqrt(java.util.Arrays.stream(m[j]).map(x -> (x - mean) * (x - mean)).sum() / (n - 1)) : 0;
                double half = tCritical(n - 1) * sd / Math.sqrt(n);
                o.put("mean", mean);
                o.put("sd", sd);
                o.putArray("ci95").add(mean - half).add(mean + half);
                o.put("min", java.util.Arrays.stream(m[j]).min().orElse(Double.NaN));
                o.put("max", java.util.Arrays.stream(m[j]).max().orElse(Double.NaN));
            }
            Results.append(out, r);
            System.out.printf("noise sigma %.2f over %d seeds: clicks %+.2f%% %s  cpc %+.2f%% %s  eff %.4f %s%n", e.getKey(), n,
                    r.get(names[0]).get("mean").asDouble(), r.get(names[0]).get("ci95"),
                    r.get(names[1]).get("mean").asDouble(), r.get(names[1]).get("ci95"),
                    r.get(names[2]).get("mean").asDouble(), r.get(names[2]).get("ci95"));
        }
    }

    /** Two-sided 95% Student t critical value. */
    static double tCritical(int df) {
        double[] t = {Double.NaN, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, 2.201, 2.179,
                2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086};
        if (df <= 0) return Double.NaN;
        return df < t.length ? t[df] : 1.96 + 2.4 / df;
    }

    /** {@code <name>_pct} and {@code <name>_ci95} = [lo, hi], in percent. */
    static void putCi(ObjectNode n, String name, Interval iv) {
        n.put(name + "_pct", 100 * iv.point());
        n.putArray(name + "_ci95").add(100 * iv.lo()).add(100 * iv.hi());
    }
}
