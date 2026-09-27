package adserve.sim.pacing;

import ads.v1.AdResponse;
import ads.v1.Impression;
import adserve.core.caps.InMemoryCapStore;
import adserve.core.caps.Windows;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.DecisionEngine;
import adserve.core.engine.DecisionLog;
import adserve.core.engine.EngineConfig;
import adserve.core.engine.FleetBudgetLedger;
import adserve.core.engine.PacingController;
import adserve.core.engine.SnapshotSource;
import adserve.core.engine.StageTimer;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.model.PacerKind;
import adserve.core.pacing.OraclePacer;
import adserve.core.pacing.Pacer;
import adserve.core.pacing.Pacers;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.DpSolver;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;
import adserve.sim.Results;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BiFunction;

/**
 * Experiment 2: every campaign's budget over one real day, under each pacer.
 *
 * <p>The whole replay day goes through the real {@link DecisionEngine} (targeting, policy, caps,
 * pacing, DP pod assembly) on a simulated fleet of eight serving nodes. Each node checks budgets
 * against the global spend as of its last sync (every 10 simulated seconds) plus its own spend
 * since. Every pacer runs twice: with each node limited to its share of the remaining budget
 * between syncs (AdServe's design) and without (every node may spend the whole remainder, which
 * is how a fleet overshoots). Pacers see global spend and update every minute against a plan
 * built from the previous day's eligible traffic.
 *
 * <p>Every pacer is scored against the same plan: spend in proportion to the campaign's eligible
 * traffic on the replay day itself. Only the oracle sees that curve in advance; the others plan
 * against the previous day.
 */
public final class PacingExperiment {
    static final int SLOTS = 1440;

    public static void main(String[] a) throws Exception {
        Path campaignsFile = Path.of(a.length > 0 ? a[0] : "data/work/campaigns.json");
        Path dayFile = Path.of(a.length > 1 ? a[1] : "data/work/requests-20130611.bin");
        Path forecastFile = Path.of(a.length > 2 ? a[2] : "data/work/forecast.json");
        int nodes = a.length > 3 ? Integer.parseInt(a[3]) : 8;
        long syncMs = a.length > 4 ? Long.parseLong(a[4]) : 10_000;

        List<Campaign> base = CampaignFiles.read(campaignsFile);
        Map<String, double[]> forecast = CampaignFiles.mapper().readValue(forecastFile.toFile(), new TypeReference<>() {});
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < base.size(); i++) index.put(base.get(i).id(), i);

        // The scoring plan: the replay day's own eligible traffic per campaign. Only the oracle sees it.
        Map<String, double[]> actual = Forecast.build(base, dayFile);
        long[][] planWeights = new long[base.size()][];
        for (int i = 0; i < base.size(); i++) {
            double[] w = actual.get(base.get(i).id());
            planWeights[i] = new long[SLOTS];
            for (int sl = 0; sl < SLOTS; sl++) planWeights[i][sl] = Math.round(w[sl] * 1000);
        }

        ObjectNode curves = Results.json().createObjectNode();
        ArrayNode hours = curves.putArray("hour");
        for (int h = 1; h <= 24; h++) hours.add(h);
        curves.set("plan", hourly(planCurve(planWeights, base)));

        for (boolean split : new boolean[]{true, false}) {
            for (PacerKind kind : PacerKind.values()) {
                BiFunction<Campaign, PacingPlan, Pacer> factory = kind == PacerKind.ORACLE
                        ? (c, p) -> {
                            PacingPlan perfect = plan(actual, c);
                            return new OraclePacer(perfect, Pacers.warmStart(c, perfect, PacingController.maxValue(c)));
                        }
                        : (c, p) -> Pacers.create(c.pacer(), p, Pacers.warmStart(c, p, PacingController.maxValue(c)));
                long s0 = System.nanoTime();
                Run r = run(withPacer(base, kind, false), dayFile, nodes, syncMs, split, index, factory, forecast);
                double secs = (System.nanoTime() - s0) / 1e9;
                report(kind, split, base, planWeights, r, nodes, syncMs, secs, curves);
            }
        }
        Results.json().writeValue(Path.of("results", "exp2_pacing_curves.json").toFile(), curves);
    }

    static List<Campaign> withPacer(List<Campaign> cs, PacerKind kind, boolean unlimited) {
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(),
                    unlimited ? Long.MAX_VALUE / 4 : c.dailyBudgetMicros(), c.flightStartMs(), c.flightEndMs(),
                    kind, c.cap(), c.targeting(), c.creatives(), c.active()));
        }
        return out;
    }

    record Run(long[][] spend, long decisions, long impressions, long capUnknown) {}

    static Run run(List<Campaign> cs, Path dayFile, int nodes, long syncMs, boolean split, Map<String, Integer> index,
                   BiFunction<Campaign, PacingPlan, Pacer> factory, Map<String, double[]> forecast) throws Exception {
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        BudgetLedger truth = new BudgetLedger();
        PacingController pacing = new PacingController(60_000L, c -> plan(forecast, c), factory, truth);
        InMemoryCapStore caps = new InMemoryCapStore(true);
        SplittableRandom rnd = new SplittableRandom(20130611L);
        EngineConfig cfg = EngineConfig.defaults().withLogCandidates(false);
        TokenCodec tokens = new TokenCodec("pacing-experiment-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        Map<String, Long> budgets = new HashMap<>();
        for (Campaign c : cs) budgets.put(c.id(), c.dailyBudgetMicros());
        FleetBudgetLedger[] ledgers = new FleetBudgetLedger[nodes];
        DecisionEngine[] engines = new DecisionEngine[nodes];
        for (int k = 0; k < nodes; k++) {
            ledgers[k] = new FleetBudgetLedger(truth, nodes, split, budgets::get);
            engines[k] = new DecisionEngine(cfg, SnapshotSource.fixed(snap), caps, ledgers[k], pacing, new DpSolver(),
                    tokens, DecisionLog.NONE, BrandSafety.defaults(), v -> List.of(), rnd::nextDouble, StageTimer.NONE);
        }
        List<String> ids = cs.stream().map(Campaign::id).toList();
        long[][] spend = new long[cs.size()][SLOTS];
        long[] nextSync = {Long.MIN_VALUE};
        long[] counts = new long[2];
        RequestFiles.forEach(dayFile, req -> {
            long ts = req.getTsMs();
            if (ts >= nextSync[0]) {
                for (FleetBudgetLedger l : ledgers) l.sync(ids, Windows.day(ts));
                nextSync[0] = (ts / syncMs + 1) * syncMs;
            }
            int node = Math.floorMod(req.getRequestId().hashCode(), nodes);
            AdResponse resp = engines[node].decide(req).response();
            counts[0]++;
            int slot = (int) (Math.floorMod(ts, 86_400_000L) / 60_000L);
            for (Impression imp : resp.getPodList()) {
                spend[index.get(imp.getCreative().getCampaignId())][slot] += imp.getPriceMicros();
                counts[1]++;
            }
        });
        long unknown = 0;
        for (DecisionEngine e : engines) unknown += e.capUnknown();
        return new Run(spend, counts[0], counts[1], unknown);
    }

    static PacingPlan plan(Map<String, double[]> forecast, Campaign c) {
        double[] w = forecast.getOrDefault(c.id(), forecast.get("*"));
        return w == null || w.length != SLOTS ? PacingPlan.flat(SLOTS) : new PacingPlan(w, 86_400_000L);
    }

    /** Budget-weighted cumulative plan fraction per minute (all campaigns together). */
    static double[] planCurve(long[][] planWeights, List<Campaign> cs) {
        double[] out = new double[SLOTS];
        double budgets = 0;
        for (int i = 0; i < cs.size(); i++) {
            double[] p = cumulativeFraction(planWeights[i]);
            double b = cs.get(i).dailyBudgetMicros();
            budgets += b;
            for (int s = 0; s < SLOTS; s++) out[s] += p[s] * b;
        }
        for (int s = 0; s < SLOTS; s++) out[s] /= budgets;
        return out;
    }

    static double[] cumulativeFraction(long[] perSlot) {
        double total = Arrays.stream(perSlot).sum();
        double[] out = new double[perSlot.length];
        double run = 0;
        for (int s = 0; s < perSlot.length; s++) {
            run += perSlot[s];
            out[s] = total > 0 ? run / total : (s + 1.0) / perSlot.length;
        }
        return out;
    }

    static double median(double[] x) {
        double[] y = x.clone();
        Arrays.sort(y);
        return y.length == 0 ? 0 : (y.length % 2 == 1 ? y[y.length / 2] : (y[y.length / 2 - 1] + y[y.length / 2]) / 2);
    }

    static ArrayNode hourly(double[] perMinute) {
        ArrayNode n = Results.json().createArrayNode();
        for (int h = 1; h <= 24; h++) n.add(Math.round(perMinute[h * 60 - 1] * 10000) / 10000.0);
        return n;
    }

    static void report(PacerKind kind, boolean split, List<Campaign> cs, long[][] planWeights, Run r, int nodes, long syncMs,
                       double secs, ObjectNode curves) throws Exception {
        int n = cs.size();
        double sumDelivered = 0, sumAbsLanding = 0, maxAbsLanding = 0, sumOver = 0, maxOver = 0, sumRmse = 0, maxDev = 0;
        double budgetTotal = 0, spendTotal = 0, overspendMicros = 0, sumExhaust = 0;
        int exhausted = 0, overspent = 0, within5 = 0;
        double[] agg = new double[SLOTS];
        double[] landings = new double[n];
        for (int i = 0; i < n; i++) {
            Campaign c = cs.get(i);
            double budget = c.dailyBudgetMicros();
            double[] plan = cumulativeFraction(planWeights[i]);
            double cum = 0, se = 0, dev = 0;
            Double exhaustHour = null;
            for (int s = 0; s < SLOTS; s++) {
                cum += r.spend[i][s];
                agg[s] += cum;
                double f = cum / budget;
                se += (f - plan[s]) * (f - plan[s]);
                dev = Math.max(dev, Math.abs(f - plan[s]));
                if (exhaustHour == null && f >= 0.995) exhaustHour = s / 60.0;
            }
            double delivered = cum / budget;
            double over = Math.max(0, cum - budget) / budget;
            double rmse = Math.sqrt(se / SLOTS);
            ObjectNode row = Results.line("exp2_pacing_campaign");
            row.remove("machine");
            row.put("pacer", kind.wire());
            row.put("allowance_split", split);
            row.put("campaign", c.id());
            row.put("advertiser", c.advertiserId());
            row.put("budget_micros", c.dailyBudgetMicros());
            row.put("spend_micros", (long) cum);
            row.put("delivered_fraction", delivered);
            row.put("overspend_fraction", over);
            if (exhaustHour == null) row.putNull("exhausted_at_hour");
            else row.put("exhausted_at_hour", exhaustHour);
            row.put("rmse_vs_plan", rmse);
            row.put("max_gap_to_plan", dev);
            Results.append("exp2_pacing_campaigns.jsonl", row);

            sumDelivered += delivered;
            double landing = Math.abs(1 - delivered);
            landings[i] = landing;
            sumAbsLanding += landing;
            maxAbsLanding = Math.max(maxAbsLanding, landing);
            if (landing <= 0.05) within5++;
            sumOver += over;
            maxOver = Math.max(maxOver, over);
            if (over > 0) overspent++;
            overspendMicros += Math.max(0, cum - budget);
            sumRmse += rmse;
            maxDev = Math.max(maxDev, dev);
            budgetTotal += budget;
            spendTotal += cum;
            if (exhaustHour != null && exhaustHour < 23.0) {
                exhausted++;
                sumExhaust += exhaustHour;
            }
        }
        for (int s = 0; s < SLOTS; s++) agg[s] /= budgetTotal;
        curves.set(kind.wire() + (split ? "" : "_no_split"), hourly(agg));

        ObjectNode out = Results.line("exp2_pacing");
        out.put("pacer", kind.wire());
        out.put("allowance_split", split);
        out.put("traffic", "iPinYou season 2, 2013-06-11: 1,745,722 ad breaks; forecast from 2013-06-10; simulated viewers");
        out.put("campaigns", n);
        out.put("serving_nodes", nodes);
        out.put("budget_sync_ms", syncMs);
        out.put("pacing_slot_s", 60);
        out.put("decisions", r.decisions);
        out.put("impressions", r.impressions);
        out.put("mean_delivered_fraction", sumDelivered / n);
        out.put("aggregate_delivered_fraction", spendTotal / budgetTotal);
        out.put("mean_abs_landing_error", sumAbsLanding / n);
        out.put("median_abs_landing_error", median(landings));
        out.put("max_abs_landing_error", maxAbsLanding);
        out.put("campaigns_within_5pct_of_budget", within5);
        out.put("mean_overspend_fraction", sumOver / n);
        out.put("max_overspend_fraction", maxOver);
        out.put("aggregate_overspend_fraction", overspendMicros / budgetTotal);
        out.put("campaigns_overspent", overspent);
        out.put("campaigns_exhausted_before_hour_23", exhausted);
        if (exhausted == 0) out.putNull("mean_exhaustion_hour");
        else out.put("mean_exhaustion_hour", sumExhaust / exhausted);
        out.put("mean_rmse_vs_plan", sumRmse / n);
        out.put("max_gap_to_plan", maxDev);
        out.put("wall_seconds", secs);
        Results.append("exp2_pacing.jsonl", out);
        System.out.println(out.toPrettyString());
    }
}
