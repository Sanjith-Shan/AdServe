package adserve.sim.caps;

import ads.v1.AdRequest;
import ads.v1.Beacon;
import ads.v1.DecisionRecord;
import ads.v1.EventType;
import ads.v1.Impression;
import ads.v1.Priority;
import ads.v1.ScoredCandidate;
import adserve.beacons.BeaconProcessor;
import adserve.beacons.RedisCounters;
import adserve.core.caps.CapCounts;
import adserve.core.caps.CapKeys;
import adserve.core.caps.CapStore;
import adserve.core.caps.Windows;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.Decision;
import adserve.core.engine.DecisionEngine;
import adserve.core.engine.DecisionLog;
import adserve.core.engine.EngineConfig;
import adserve.core.engine.PacingController;
import adserve.core.engine.SnapshotSource;
import adserve.core.engine.StageTimer;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.model.PacerKind;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.DpSolver;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisFuture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Experiment 3: does any viewer see an ad past its cap when beacons are duplicated, late or lost?
 *
 * <p>Simulated viewing sessions: {@code viewers} viewers, each taken with its real context (geo,
 * device, segments) from the replay day, watch one title for 12 ad breaks spaced 12 to 20 minutes
 * apart. Every break goes through the real decision engine against real Redis. Every served
 * impression fires an IMPRESSION beacon that is duplicated with probability {@code dup} (and again
 * with that probability, up to three extra copies), lost with probability 1%, and delayed 0.5 to
 * 30 s (97%) or 5 to 60 min (3%, a device that went offline). Beacons go through the beacon
 * consumer's processor in arrival order, interleaved with decisions on one simulated clock.
 *
 * <p>Four counter designs:
 * <ul>
 *   <li>{@code idempotent}: AdServe's design, the Lua script at decision time and from beacons;</li>
 *   <li>{@code naive}: the same two writers with a bare INCR;</li>
 *   <li>{@code naive_beacon_only}: INCR from beacons only, the common "count what the device
 *       confirmed" design;</li>
 *   <li>{@code idempotent_beacon_only}: the script, beacons only, to separate the two effects.</li>
 * </ul>
 * Ground truth is the set of impressions actually served. A violation is a served impression
 * beyond a campaign's daily or weekly cap, or beyond the viewer's hourly ad-load cap.
 */
public final class CapExperiment {

    enum Mode {
        IDEMPOTENT(true, true), NAIVE(false, true), NAIVE_BEACON_ONLY(false, false), IDEMPOTENT_BEACON_ONLY(true, false);

        final boolean idempotent;
        final boolean decisionTimeWrite;

        Mode(boolean idempotent, boolean decisionTimeWrite) {
            this.idempotent = idempotent;
            this.decisionTimeWrite = decisionTimeWrite;
        }
    }

    record Pending(long atMs, Beacon beacon, long seq) {}

    public static void main(String[] a) throws Exception {
        Path campaignsFile = Path.of(a.length > 0 ? a[0] : "data/work/campaigns.json");
        Path dayFile = Path.of(a.length > 1 ? a[1] : "data/work/requests-20130611.bin");
        int viewers = a.length > 2 ? Integer.parseInt(a[2]) : 2000;
        String redis = a.length > 3 ? a[3] : "redis://localhost:26379";

        List<Campaign> cs = new ArrayList<>();
        for (Campaign c : CampaignFiles.read(campaignsFile)) {
            cs.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), Long.MAX_VALUE / 4,
                    c.flightStartMs(), c.flightEndMs(), PacerKind.UNPACED, c.cap(), c.targeting(), c.creatives(), true));
        }
        List<AdRequest> seeds = sampleViewers(dayFile, viewers);
        for (double dup : new double[]{0.05, 0.10, 0.20}) {
            for (Mode m : Mode.values()) {
                run(cs, seeds, m, dup, redis);
            }
        }
    }

    /** One request per distinct viewer, spread over the file. */
    static List<AdRequest> sampleViewers(Path dayFile, int n) throws Exception {
        List<AdRequest> all = RequestFiles.readAll(dayFile, 400_000);
        Map<String, AdRequest> byViewer = new HashMap<>();
        SplittableRandom r = new SplittableRandom(7);
        for (AdRequest q : all) {
            if (q.getViewerId().startsWith("anon-")) continue;
            byViewer.putIfAbsent(q.getViewerId(), q);
        }
        List<AdRequest> pool = new ArrayList<>(byViewer.values());
        pool.sort((x, y) -> x.getViewerId().compareTo(y.getViewerId()));
        List<AdRequest> out = new ArrayList<>();
        for (int i = 0; i < n && !pool.isEmpty(); i++) out.add(pool.remove(r.nextInt(pool.size())));
        return out;
    }

    static void run(List<Campaign> cs, List<AdRequest> seeds, Mode mode, double dup, String redisUri) throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 6);
        SplittableRandom rnd = new SplittableRandom(Double.doubleToLongBits(dup) ^ 11L);
        TokenCodec tokens = new TokenCodec("cap-experiment-key-0123456789abcdef!".getBytes(StandardCharsets.UTF_8));
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        Map<String, Campaign> byId = new HashMap<>();
        for (Campaign c : cs) byId.put(c.id(), c);

        try (RedisCounters counters = new RedisCounters(redisUri, 1000, mode.idempotent)) {
            CapStore store = mode.decisionTimeWrite ? counters : new CapStore() {
                public CapCounts fetch(String v, List<String> ids, long now) {
                    return counters.fetch(v, ids, now);
                }

                public void recordImpression(String v, String c, String e, long ts) {}
            };
            BudgetLedger ledger = new BudgetLedger();
            PacingController pacing = PacingController.standard(60_000, c -> PacingPlan.flat(1440), ledger);
            SplittableRandom engineRnd = new SplittableRandom(3);
            DecisionEngine engine = new DecisionEngine(EngineConfig.defaults(), SnapshotSource.fixed(snap), store, ledger,
                    pacing, new DpSolver(), tokens, DecisionLog.NONE, BrandSafety.defaults(), v -> List.of(),
                    engineRnd::nextDouble, StageTimer.NONE);
            BeaconProcessor beacons = new BeaconProcessor(tokens, counters, null);

            // Build every break of every session, then run decisions and beacon deliveries in time order.
            List<AdRequest> breaks = new ArrayList<>();
            long dayStart = Windows.day(seeds.get(0).getTsMs()) * Windows.DAY_MS;
            for (AdRequest s : seeds) {
                String viewer = "x" + run + "-" + s.getViewerId();
                long t = dayStart + 3_600_000L + (long) (rnd.nextDouble() * 18 * 3_600_000L);
                for (int b = 0; b < 12; b++) {
                    breaks.add(s.toBuilder().setViewerId(viewer).setRequestId(viewer + "-" + b)
                            .setBreakLengthS(90).setPriority(Priority.VOD).setTsMs(t).build());
                    t += 12 * 60_000L + (long) (rnd.nextDouble() * 8 * 60_000L);
                }
            }
            breaks.sort((x, y) -> Long.compare(x.getTsMs(), y.getTsMs()));

            PriorityQueue<Pending> queue = new PriorityQueue<>((x, y) -> x.atMs != y.atMs ? Long.compare(x.atMs, y.atMs) : Long.compare(x.seq, y.seq));
            long seq = 0;
            Map<String, Integer> servedDay = new HashMap<>();
            Map<String, Integer> servedWeek = new HashMap<>();
            Map<String, Integer> servedHour = new HashMap<>();
            long impressions = 0, duplicates = 0, lost = 0, late = 0, violations = 0, adLoadViolations = 0;
            long wronglyRefused = 0, correctlyRefused = 0;
            List<RedisFuture<?>> inflight = new ArrayList<>();
            for (AdRequest req : breaks) {
                // Deliver every beacon due before this break.
                while (!queue.isEmpty() && queue.peek().atMs <= req.getTsMs()) {
                    inflight.addAll(beacons.process(queue.poll().beacon));
                }
                drain(inflight);
                Decision d = engine.decide(req);
                DecisionRecord rec = d.record();
                long day = Windows.day(req.getTsMs());
                for (ScoredCandidate c : rec.getCandidatesList()) {
                    if (!c.getDroppedBy().equals("frequency_cap")) continue;
                    Campaign camp = byId.get(c.getCampaignId());
                    int trueDay = servedDay.getOrDefault(req.getViewerId() + "|" + c.getCampaignId() + "|" + day, 0);
                    int trueWeek = servedWeek.getOrDefault(req.getViewerId() + "|" + c.getCampaignId(), 0);
                    if (camp.cap().remaining(trueDay, trueWeek) > 0) wronglyRefused++;
                    else correctlyRefused++;
                }
                String hourKey = req.getViewerId() + "|" + Windows.hour(req.getTsMs());
                for (Impression imp : d.response().getPodList()) {
                    impressions++;
                    Campaign camp = byId.get(imp.getCreative().getCampaignId());
                    String k = req.getViewerId() + "|" + camp.id() + "|" + day;
                    int nd = servedDay.merge(k, 1, Integer::sum);
                    int nw = servedWeek.merge(req.getViewerId() + "|" + camp.id(), 1, Integer::sum);
                    int nh = servedHour.merge(hourKey, 1, Integer::sum);
                    if ((camp.cap().perDay() > 0 && nd > camp.cap().perDay()) || (camp.cap().perWeek() > 0 && nw > camp.cap().perWeek())) {
                        violations++;
                    }
                    if (nh > engine.config().maxAdsPerViewerHour()) adLoadViolations++;
                    // The device's IMPRESSION beacon, possibly lost, late, or repeated.
                    Beacon b = Beacon.newBuilder().setToken(imp.getToken()).setType(EventType.IMPRESSION)
                            .setClientTsMs(req.getTsMs()).setDevice(req.getDevice()).setArrivalRegion(req.getRegion())
                            .setBeaconId(imp.getImpressionId() + "-imp").setSchema("vod").build();
                    if (rnd.nextDouble() < 0.01) {
                        lost++;
                        continue;
                    }
                    int copies = 1;
                    while (copies < 4 && rnd.nextDouble() < dup) copies++;
                    duplicates += copies - 1;
                    for (int c = 0; c < copies; c++) {
                        boolean isLate = rnd.nextDouble() < 0.03;
                        if (isLate) late++;
                        long delay = isLate ? 300_000L + (long) (rnd.nextDouble() * 3_300_000L) : 500 + (long) (rnd.nextDouble() * 29_500);
                        queue.add(new Pending(req.getTsMs() + delay, b, seq++));
                    }
                }
            }
            while (!queue.isEmpty()) inflight.addAll(beacons.process(queue.poll().beacon));
            drain(inflight);

            // Final counter drift against the truth for every capped (viewer, campaign, day).
            long trueTotal = 0, counted = 0, overCounted = 0, underCounted = 0;
            List<String> keys = new ArrayList<>(servedDay.keySet());
            for (int i = 0; i < keys.size(); i += 500) {
                List<String> chunk = keys.subList(i, Math.min(keys.size(), i + 500));
                String[] rk = new String[chunk.size()];
                for (int j = 0; j < rk.length; j++) {
                    String[] p = chunk.get(j).split("\\|");
                    rk[j] = CapKeys.day(p[0], p[1], Long.parseLong(p[2]) * Windows.DAY_MS);
                }
                var vals = counters.commands().mget(rk).get(10, TimeUnit.SECONDS);
                for (int j = 0; j < rk.length; j++) {
                    long truth = servedDay.get(chunk.get(j));
                    long c = vals.get(j).hasValue() ? Long.parseLong(vals.get(j).getValue()) : 0;
                    trueTotal += truth;
                    counted += c;
                    if (c > truth) overCounted++;
                    if (c < truth) underCounted++;
                }
            }

            ObjectNode out = Results.line("exp3_caps");
            out.put("mode", mode.name().toLowerCase());
            out.put("duplicate_probability", dup);
            out.put("lost_probability", 0.01);
            out.put("late_probability", 0.03);
            out.put("traffic", "simulated sessions: " + seeds.size() + " iPinYou viewers (real geo, device, segments) x 12 breaks, 12 to 20 min apart");
            out.put("breaks", breaks.size());
            out.put("impressions_served", impressions);
            out.put("duplicated_beacon_deliveries", duplicates);
            out.put("lost_beacons", lost);
            out.put("late_beacon_deliveries", late);
            out.put("cap_violations", violations);
            out.put("ad_load_violations", adLoadViolations);
            out.put("wrongly_refused", wronglyRefused);
            out.put("correctly_refused", correctlyRefused);
            out.put("counter_total", counted);
            out.put("true_total", trueTotal);
            out.put("counter_drift_pct", trueTotal == 0 ? 0 : 100.0 * (counted - trueTotal) / trueTotal);
            out.put("keys_over_counted", overCounted);
            out.put("keys_under_counted", underCounted);
            out.put("redis", "redis:7.4 in Docker on the same machine");
            Results.append("exp3_caps.jsonl", out);
            System.out.printf("%-24s dup=%.2f served=%d dups=%d violations=%d adload=%d wrongly_refused=%d drift=%.2f%%%n",
                    mode, dup, impressions, duplicates, violations, adLoadViolations, wronglyRefused,
                    out.get("counter_drift_pct").asDouble());
        }
    }

    static void drain(List<RedisFuture<?>> fs) throws Exception {
        for (RedisFuture<?> f : fs) f.get(10, TimeUnit.SECONDS);
        fs.clear();
    }
}
