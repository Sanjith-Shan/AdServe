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
import adserve.core.auction.AuctionConfig;
import adserve.core.auction.PricingRule;
import adserve.core.model.Campaign;
import adserve.core.model.Pricing;
import adserve.core.model.PacerKind;
import adserve.core.pacing.BidShadingPacer;
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
 *
 * <p>The same harness runs experiment 14 (the exp2 table under second price, winners charged the
 * cleared price, and under first price on the same bids) and experiment 15 (the bid-scaling pacer
 * against Smart throttling and unpaced under second price). Both add impressions, cleared spend,
 * expected clicks (the sum of served creatives' click rates), cost per expected click and the
 * mean price over bid. With no flags, {@code exp2-pacing} still runs exactly what experiment 2
 * ran: first price (pay your bid), no reserve, the five exp2 pacers.
 */
public final class PacingExperiment {
    static final int SLOTS = 1440;

    /** exp2's pacers, in exp2's order. The bid-scaling pacer only runs when asked for. */
    static final List<PacerKind> EXP2_PACERS = List.of(PacerKind.UNPACED, PacerKind.THROTTLE, PacerKind.SMART,
            PacerKind.PID, PacerKind.ORACLE);

    /**
     * What one invocation runs. {@code exp2} defaults reproduce experiment 2: first price (pay
     * your bid, which is all AdServe charged before the auction), no reserve, every exp2 pacer.
     */
    record Options(String mode, String out, List<PricingRule> rules, List<PacerKind> pacers, boolean[] splits, long reserveMicros,
                   String reserveSource, double[] bidGains, double bidMaxLogStep, double bidFloor) {}

    public static void main(String[] a) throws Exception {
        run(a, "exp2");
    }

    /** Experiment 14: the exp2 table under second price with cleared prices charged, and under first price. */
    public static void exp14(String[] a) throws Exception {
        run(a, "exp14");
    }

    /** Experiment 15: the bid-scaling pacer against Smart throttling and unpaced, under second price. */
    public static void exp15(String[] a) throws Exception {
        run(a, "exp15");
    }

    /**
     * Positional arguments as in exp2 (campaigns, replay day, forecast, nodes, sync ms); flags:
     * {@code --pricing second,first}, {@code --pacers smart,bid_scale}, {@code --splits true,false},
     * {@code --reserve MICROS} (default: {@code reserve_micros} from {@code --auction-file}, itself
     * default {@code data/work/auction.json}; exp2 mode uses no reserve), {@code --out NAME}.
     */
    static void run(String[] args, String mode) throws Exception {
        List<String> a = new ArrayList<>();
        Map<String, String> flags = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) flags.put(args[i].substring(2), args[++i]);
            else a.add(args[i]);
        }
        Path campaignsFile = Path.of(a.size() > 0 ? a.get(0) : "data/work/campaigns.json");
        Path dayFile = Path.of(a.size() > 1 ? a.get(1) : "data/work/requests-20130611.bin");
        Path forecastFile = Path.of(a.size() > 2 ? a.get(2) : "data/work/forecast.json");
        int nodes = a.size() > 3 ? Integer.parseInt(a.get(3)) : 8;
        long syncMs = a.size() > 4 ? Long.parseLong(a.get(4)) : 10_000;
        Options o = options(mode, flags);

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
        RunInfo info = new RunInfo(o, campaignsFile, dayFile, creativeFacts(base));
        // The campaigns' flights are the replay day (2013-06-11). Tuning on another day (the
        // forecast day, 2013-06-10) moves every flight to that day; nothing else changes.
        long shiftMs = flightShiftMs(base, dayFile);
        List<Campaign> flown = shiftFlights(base, shiftMs);
        info = info.withShift(shiftMs);

        ObjectNode curves = Results.json().createObjectNode();
        ArrayNode hours = curves.putArray("hour");
        for (int h = 1; h <= 24; h++) hours.add(h);
        curves.set("plan", hourly(planCurve(planWeights, base)));

        for (PricingRule rule : o.rules()) {
            EngineConfig cfg = EngineConfig.defaults().withLogCandidates(false)
                    .withAuction(AuctionConfig.defaults().withPricing(rule).withReserve(o.reserveMicros()));
            for (boolean split : o.splits()) {
                for (PacerKind kind : o.pacers()) {
                  for (double gain : kind == PacerKind.BID_SCALE ? o.bidGains() : new double[]{Double.NaN}) {
                    BiFunction<Campaign, PacingPlan, Pacer> factory = kind == PacerKind.ORACLE
                            ? (c, p) -> {
                                PacingPlan perfect = plan(actual, c);
                                return new OraclePacer(perfect, Pacers.warmStart(c, perfect, PacingController.maxValue(c)));
                            }
                            : kind == PacerKind.BID_SCALE
                            ? (c, p) -> new BidShadingPacer(p, Pacers.warmStart(c, p, PacingController.maxValue(c)),
                                    gain, o.bidMaxLogStep(), o.bidFloor())
                            : (c, p) -> Pacers.create(c.pacer(), p, Pacers.warmStart(c, p, PacingController.maxValue(c)));
                    long s0 = System.nanoTime();
                    Run r = run(withPacer(flown, kind, false), dayFile, nodes, syncMs, split, index, factory, forecast,
                            cfg, info.creatives());
                    double secs = (System.nanoTime() - s0) / 1e9;
                    report(kind, split, rule, base, planWeights, r, nodes, syncMs, secs, curves, info, gain);
                  }
                }
            }
        }
        Results.json().writeValue(Path.of("results", o.out() + "_curves.json").toFile(), curves);
    }

    static Options options(String mode, Map<String, String> flags) throws Exception {
        String out = switch (mode) {
            case "exp14" -> "exp14_pacing_pricing";
            case "exp15" -> "exp15_bid_pacing";
            default -> "exp2_pacing";
        };
        String rules = mode.equals("exp2") ? "first" : mode.equals("exp14") ? "second,first" : "second";
        String pacers = mode.equals("exp15") ? "bid_scale,smart,unpaced" : "unpaced,throttle,smart,pid,oracle";
        rules = flags.getOrDefault("pricing", rules);
        pacers = flags.getOrDefault("pacers", pacers);
        out = flags.getOrDefault("out", out);
        List<PricingRule> rs = new ArrayList<>();
        for (String r : rules.split(",")) {
            rs.add(switch (r.trim()) {
                case "second", "second_price" -> PricingRule.SECOND_PRICE;
                case "first", "first_price" -> PricingRule.FIRST_PRICE;
                default -> throw new IllegalArgumentException("unknown pricing rule " + r);
            });
        }
        List<PacerKind> ps = new ArrayList<>();
        for (String k : pacers.split(",")) ps.add(PacerKind.valueOf(k.trim().toUpperCase()));
        String[] sp = flags.getOrDefault("splits", "true,false").split(",");
        boolean[] splits = new boolean[sp.length];
        for (int i = 0; i < sp.length; i++) splits[i] = Boolean.parseBoolean(sp[i].trim());

        long reserve;
        String source;
        if (flags.containsKey("reserve")) {
            reserve = Long.parseLong(flags.get("reserve"));
            source = "--reserve flag";
        } else if (mode.equals("exp2")) {
            reserve = 0;
            source = "none (experiment 2 predates the auction)";
        } else {
            Path f = Path.of(flags.getOrDefault("auction-file", "data/work/auction.json"));
            if (!java.nio.file.Files.exists(f)) {
                throw new IllegalStateException(f + " is missing: it carries reserve_micros. Derive it first, or pass --reserve MICROS.");
            }
            var node = CampaignFiles.mapper().readTree(f.toFile()).get("reserve_micros");
            if (node == null || !node.canConvertToLong()) throw new IllegalStateException(f + " has no reserve_micros");
            reserve = node.asLong();
            source = f.toString();
        }
        return new Options(mode, out, rs, ps, splits, reserve, source,
                Arrays.stream(flags.getOrDefault("bid-gain", String.valueOf(BidShadingPacer.DEFAULT_GAIN)).split(","))
                        .mapToDouble(x -> Double.parseDouble(x.trim())).toArray(),
                Math.log(Double.parseDouble(flags.getOrDefault("bid-max-step", String.valueOf(Math.exp(BidShadingPacer.DEFAULT_MAX_LOG_STEP))))),
                Double.parseDouble(flags.getOrDefault("bid-floor", String.valueOf(BidShadingPacer.DEFAULT_FLOOR))));
    }

    /** Click rate and unshaded bid value of every creative, by creative id. */
    record CreativeFacts(Map<String, Double> clickRate, Map<String, Long> fullBid) {}

    static CreativeFacts creativeFacts(List<Campaign> cs) {
        Map<String, Double> ctr = new HashMap<>();
        Map<String, Long> bid = new HashMap<>();
        for (Campaign c : cs) {
            for (var cr : c.creatives()) {
                ctr.put(c.id() + "/" + cr.id(), cr.clickRate());
                bid.put(c.id() + "/" + cr.id(), Pricing.impressionValueMicros(c.cpcBidMicros(), cr.clickRate(), cr.durationS()));
            }
        }
        return new CreativeFacts(ctr, bid);
    }

    record RunInfo(Options options, Path campaignsFile, Path dayFile, CreativeFacts creatives, long flightShiftMs) {
        RunInfo(Options options, Path campaignsFile, Path dayFile, CreativeFacts creatives) {
            this(options, campaignsFile, dayFile, creatives, 0);
        }

        RunInfo withShift(long ms) {
            return new RunInfo(options, campaignsFile, dayFile, creatives, ms);
        }

        String replayDay() {
            var m = java.util.regex.Pattern.compile("(\\d{4})(\\d{2})(\\d{2})").matcher(dayFile.getFileName().toString());
            return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) : dayFile.getFileName().toString();
        }

        String previousDay() {
            try {
                return java.time.LocalDate.parse(replayDay()).minusDays(1).toString();
            } catch (java.time.format.DateTimeParseException e) {
                return "the previous day";
            }
        }
    }

    /** Whole days between the campaigns' flight and the day file's first request (0 when it is in flight). */
    static long flightShiftMs(List<Campaign> cs, Path dayFile) throws Exception {
        long[] first = {-1};
        try {
            RequestFiles.forEach(dayFile, r -> {
                if (first[0] < 0) {
                    first[0] = r.getTsMs();
                    throw new StopReading();
                }
            });
        } catch (StopReading ignored) {
            // read one request only
        }
        if (first[0] < 0 || cs.isEmpty() || cs.get(0).inFlight(first[0])) return 0;
        long days = Math.floorDiv(first[0], Windows.DAY_MS) - Math.floorDiv(cs.get(0).flightStartMs(), Windows.DAY_MS);
        return days * Windows.DAY_MS;
    }

    static final class StopReading extends RuntimeException {
        StopReading() {
            super(null, null, false, false);
        }
    }

    static List<Campaign> shiftFlights(List<Campaign> cs, long shiftMs) {
        if (shiftMs == 0) return cs;
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), c.dailyBudgetMicros(),
                    c.flightStartMs() + shiftMs, c.flightEndMs() + shiftMs, c.pacer(), c.cap(), c.targeting(),
                    c.creatives(), c.active()));
        }
        return out;
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

    /**
     * @param impressionsBy     impressions per campaign
     * @param clicksBy          expected clicks per campaign: the sum of served creatives' click rates
     * @param priceOverBid      sum over impressions of price / the bid that entered the auction
     * @param priceOverFullBid  sum over impressions of price / the unshaded bid value
     */
    record Run(long[][] spend, long decisions, long impressions, long capUnknown, long[] impressionsBy,
               double[] clicksBy, double priceOverBid, double priceOverFullBid) {}

    static Run run(List<Campaign> cs, Path dayFile, int nodes, long syncMs, boolean split, Map<String, Integer> index,
                   BiFunction<Campaign, PacingPlan, Pacer> factory, Map<String, double[]> forecast,
                   EngineConfig cfg, CreativeFacts facts) throws Exception {
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        BudgetLedger truth = new BudgetLedger();
        PacingController pacing = new PacingController(60_000L, c -> plan(forecast, c), factory, truth);
        InMemoryCapStore caps = new InMemoryCapStore(true);
        SplittableRandom rnd = new SplittableRandom(20130611L);
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
        long[] impsBy = new long[cs.size()];
        double[] clicksBy = new double[cs.size()];
        double[] ratios = new double[2];
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
                int ci = index.get(imp.getCreative().getCampaignId());
                spend[ci][slot] += imp.getPriceMicros();
                counts[1]++;
                String key = imp.getCreative().getCampaignId() + "/" + imp.getCreative().getCreativeId();
                impsBy[ci]++;
                clicksBy[ci] += facts.clickRate().get(key);
                if (imp.getBidMicros() > 0) ratios[0] += (double) imp.getPriceMicros() / imp.getBidMicros();
                long full = facts.fullBid().get(key);
                if (full > 0) ratios[1] += (double) imp.getPriceMicros() / full;
            }
        });
        long unknown = 0;
        for (DecisionEngine e : engines) unknown += e.capUnknown();
        return new Run(spend, counts[0], counts[1], unknown, impsBy, clicksBy, ratios[0], ratios[1]);
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

    static void report(PacerKind kind, boolean split, PricingRule rule, List<Campaign> cs, long[][] planWeights, Run r,
                       int nodes, long syncMs, double secs, ObjectNode curves, RunInfo info, double gain) throws Exception {
        String out = info.options().out();
        boolean exp2 = info.options().mode().equals("exp2");
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
            ObjectNode row = Results.line(out + "_campaign");
            row.remove("machine");
            row.put("pacer", kind.wire());
            if (!exp2) row.put("pricing", rule.wire());
            if (!Double.isNaN(gain)) row.put("bid_gain", gain);
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
            if (!exp2) {
                row.put("impressions", r.impressionsBy()[i]);
                row.put("expected_clicks", r.clicksBy()[i]);
            }
            Results.append(out + "_campaigns.jsonl", row);

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
        curves.set((exp2 ? "" : rule.wire() + "_") + kind.wire() + (Double.isNaN(gain) || info.options().bidGains().length == 1 ? "" : "_g" + gain)
                + (split ? "" : "_no_split"), hourly(agg));

        ObjectNode row = Results.line(out);
        row.put("pacer", kind.wire());
        if (!exp2) {
            row.put("pricing", rule.wire());
            row.put("reserve_micros", info.options().reserveMicros());
            row.put("reserve_source", info.options().reserveSource());
            row.put("campaigns_file", info.campaignsFile().toString());
            row.put("replay_day", info.replayDay());
            if (info.flightShiftMs() != 0) row.put("flights_shifted_days", info.flightShiftMs() / Windows.DAY_MS);
            if (kind == PacerKind.BID_SCALE) {
                ObjectNode bp = row.putObject("bid_scale_params");
                bp.put("gain", gain);
                bp.put("max_step_factor", Math.exp(info.options().bidMaxLogStep()));
                bp.put("floor", info.options().bidFloor());
                bp.put("warm_start", "Pacers.warmStart (budget over forecast requests x best bid value)");
            }
        }
        row.put("allowance_split", split);
        row.put("traffic", String.format("iPinYou season 2, %s: %,d ad breaks; forecast from %s; simulated viewers",
                info.replayDay(), r.decisions, info.previousDay()));
        row.put("campaigns", n);
        row.put("serving_nodes", nodes);
        row.put("budget_sync_ms", syncMs);
        row.put("pacing_slot_s", 60);
        row.put("decisions", r.decisions);
        row.put("impressions", r.impressions);
        row.put("mean_delivered_fraction", sumDelivered / n);
        row.put("aggregate_delivered_fraction", spendTotal / budgetTotal);
        row.put("mean_abs_landing_error", sumAbsLanding / n);
        row.put("median_abs_landing_error", median(landings));
        row.put("max_abs_landing_error", maxAbsLanding);
        row.put("campaigns_within_5pct_of_budget", within5);
        row.put("mean_overspend_fraction", sumOver / n);
        row.put("max_overspend_fraction", maxOver);
        row.put("aggregate_overspend_fraction", overspendMicros / budgetTotal);
        row.put("campaigns_overspent", overspent);
        row.put("campaigns_exhausted_before_hour_23", exhausted);
        if (exhausted == 0) row.putNull("mean_exhaustion_hour");
        else row.put("mean_exhaustion_hour", sumExhaust / exhausted);
        row.put("mean_rmse_vs_plan", sumRmse / n);
        row.put("max_gap_to_plan", maxDev);
        if (!exp2) {
            double clicks = Arrays.stream(r.clicksBy()).sum();
            row.put("total_cleared_spend_micros", (long) spendTotal);
            row.put("expected_clicks", clicks);
            row.put("cost_per_expected_click_micros", clicks > 0 ? spendTotal / clicks : 0);
            row.put("cost_per_thousand_impressions_micros", r.impressions > 0 ? spendTotal * 1000 / r.impressions : 0);
            row.put("mean_price_over_bid", r.impressions > 0 ? r.priceOverBid() / r.impressions : 0);
            row.put("mean_price_over_unshaded_bid", r.impressions > 0 ? r.priceOverFullBid() / r.impressions : 0);
        }
        row.put("wall_seconds", secs);
        Results.append(out + ".jsonl", row);
        System.out.println(row.toPrettyString());
    }
}
