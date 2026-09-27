package adserve.core.engine;

import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Creative;
import ads.v1.DecisionRecord;
import ads.v1.EventType;
import ads.v1.Impression;
import ads.v1.ScoredCandidate;
import ads.v1.Token;
import adserve.core.caps.CapCounts;
import adserve.core.caps.CapMode;
import adserve.core.caps.CapStore;
import adserve.core.caps.CapStoreUnavailableException;
import adserve.core.caps.EventIds;
import adserve.core.caps.Windows;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;
import adserve.core.pod.PodSolver;
import adserve.core.pod.PodValidator;
import adserve.core.policy.BrandSafety;
import adserve.core.targeting.RequestContext;
import adserve.core.token.TokenCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * The decision path: resolve, targeting, policy, frequency cap, pacing, selection and pod
 * assembly, tokens, log. One call per ad break. The only I/O is the single counter fetch in the
 * frequency-cap stage; the decision log and the counter increments are handed off and never
 * awaited, and nothing here touches a database.
 */
public final class DecisionEngine {
    public static final List<EventType> TRACKING = List.of(EventType.IMPRESSION, EventType.START,
            EventType.FIRST_QUARTILE, EventType.MIDPOINT, EventType.THIRD_QUARTILE, EventType.COMPLETE,
            EventType.CLICK);

    private final EngineConfig config;
    private final SnapshotSource snapshots;
    private final CapStore caps;
    private final BudgetLedger budget;
    private final PacingController pacing;
    private final PodSolver solver;
    private final TokenCodec tokens;
    private final DecisionLog log;
    private final BrandSafety brandSafety;
    private final Function<String, List<String>> segmentLookup;
    private final DoubleSupplier random;
    private final StageTimer timer;

    private final LongAdder invalidPods = new LongAdder();
    private final LongAdder capUnknown = new LongAdder();

    public DecisionEngine(EngineConfig config, SnapshotSource snapshots, CapStore caps, BudgetLedger budget,
                          PacingController pacing, PodSolver solver, TokenCodec tokens, DecisionLog log,
                          BrandSafety brandSafety, Function<String, List<String>> segmentLookup,
                          DoubleSupplier random, StageTimer timer) {
        this.config = config;
        this.snapshots = snapshots;
        this.caps = caps;
        this.budget = budget;
        this.pacing = pacing;
        this.solver = solver;
        this.tokens = tokens;
        this.log = log;
        this.brandSafety = brandSafety;
        this.segmentLookup = segmentLookup;
        this.random = random != null ? random : () -> ThreadLocalRandom.current().nextDouble();
        this.timer = timer;
    }

    public EngineConfig config() {
        return config;
    }

    public long invalidPods() {
        return invalidPods.sum();
    }

    public long capUnknown() {
        return capUnknown.sum();
    }

    public PacingController pacing() {
        return pacing;
    }

    public BudgetLedger budget() {
        return budget;
    }

    public Decision decide(AdRequest req) {
        final long start = System.nanoTime();
        long t = start;
        final long now = config.useRequestClock() && req.getTsMs() > 0 ? req.getTsMs() : System.currentTimeMillis();
        final long day = Windows.day(now);
        final CampaignSnapshot snap = snapshots.current();
        pacing.advance(now);

        // 1. Resolve: segments come on the request when the player already has them, else from
        // the in-process viewer cache.
        List<String> segments = req.getSegmentsCount() > 0 ? req.getSegmentsList() : segmentLookup.apply(req.getViewerId());
        RequestContext ctx = snap.targeting().context(req.getGeo(), req.getDevice(), segments, req.getGenre());
        t = lap(Stage.RESOLVE, t);

        // 2. Targeting: every campaign's compiled predicate against the request.
        int n = snap.size();
        int[] live = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (snap.targeting().matches(i, ctx)) live[m++] = i;
        }
        final int matched = m;
        String[] dropped = new String[n];
        t = lap(Stage.TARGETING, t);

        // 3. Policy: flight, budget, brand safety.
        int k = 0;
        for (int j = 0; j < m; j++) {
            int i = live[j];
            Campaign c = snap.campaign(i);
            String why = null;
            if (!c.inFlight(now)) why = "flight";
            else if (budget.spent(c.id(), day) >= c.dailyBudgetMicros()) why = "budget";
            else if (!brandSafety.allowed(c.category(), req.getGenre())) why = "brand_safety";
            if (why == null) live[k++] = i;
            else dropped[i] = why;
        }
        m = k;
        t = lap(Stage.POLICY, t);

        // 4. Frequency cap: one round trip for every surviving campaign plus the viewer's hourly load.
        String capState = "ok";
        int[] capRemaining = new int[n];
        int hourRemaining = config.maxAdsPerViewerHour();
        if (m > 0) {
            List<String> ids = new ArrayList<>(m);
            for (int j = 0; j < m; j++) ids.add(snap.campaign(live[j]).id());
            CapCounts counts;
            try {
                counts = caps.fetch(req.getViewerId(), ids, now);
            } catch (CapStoreUnavailableException e) {
                counts = CapCounts.unknown(m);
                capUnknown.increment();
            }
            if (!counts.known()) capState = config.capMode().wire();
            else hourRemaining = (int) Math.max(0, config.maxAdsPerViewerHour() - counts.viewerHour());
            k = 0;
            for (int j = 0; j < m; j++) {
                int i = live[j];
                Campaign c = snap.campaign(i);
                int rem;
                if (counts.known()) {
                    rem = c.cap().remaining(counts.day()[j], counts.week()[j]);
                } else if (config.capMode() == CapMode.UNKNOWN_DENY && c.cap().capped()) {
                    rem = 0;
                } else {
                    rem = Integer.MAX_VALUE;
                }
                if (rem > 0) {
                    capRemaining[i] = rem;
                    live[k++] = i;
                } else {
                    dropped[i] = counts.known() ? "frequency_cap" : "cap_unknown";
                }
            }
            m = k;
        }
        t = lap(Stage.FREQUENCY_CAP, t);

        // 5. Pacing gate.
        k = 0;
        for (int j = 0; j < m; j++) {
            int i = live[j];
            if (pacing.admit(snap.campaign(i), day, random.getAsDouble())) live[k++] = i;
            else dropped[i] = "pacing";
        }
        m = k;
        t = lap(Stage.PACING, t);

        // 6. Selection and pod assembly over every creative of every surviving campaign.
        List<Item> items = new ArrayList<>();
        List<int[]> refs = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            int i = live[j];
            Campaign c = snap.campaign(i);
            long remainingBudget = c.dailyBudgetMicros() - budget.spent(c.id(), day);
            for (int x = 0; x < c.creatives().size(); x++) {
                long v = snap.creativeValue(i, x);
                if (v <= 0 || v > remainingBudget) continue;
                items.add(new Item(refs.size(), i, snap.advertiser(i), snap.category(i),
                        c.creatives().get(x).durationS(), v));
                refs.add(new int[]{i, x});
            }
        }
        PodRules rules = new PodRules(req.getBreakLengthS(), config.minAds(),
                Math.min(config.maxAds(), hourRemaining), config.separation());
        Pod pod = items.isEmpty() || rules.maxAds() == 0 ? Pod.EMPTY : solver.solve(items, rules);
        if (PodValidator.violation(pod, items, rules) != null) {
            invalidPods.increment();
            pod = Pod.EMPTY;
        }
        t = lap(Stage.SELECTION, t);

        // 7. Tokens and response.
        AdResponse.Builder resp = AdResponse.newBuilder()
                .setRequestId(req.getRequestId())
                .setViewerId(req.getViewerId())
                .setServingRegion(config.servingRegion())
                .setDecidedTsMs(now)
                .setCandidatesConsidered(matched);
        String pacerName = "";
        List<String[]> served = new ArrayList<>(pod.size());
        for (int slot = 0; slot < pod.size(); slot++) {
            Item it = pod.items().get(slot);
            int[] ref = refs.get(it.ref());
            Campaign c = snap.campaign(ref[0]);
            CreativeSpec cs = c.creatives().get(ref[1]);
            String impressionId = newImpressionId();
            Token token = Token.newBuilder()
                    .setImpressionId(impressionId)
                    .setCampaignId(c.id())
                    .setCreativeId(cs.id())
                    .setServingRegion(config.servingRegion())
                    .setIssuedTsMs(now)
                    .setViewerId(req.getViewerId())
                    .setPriceMicros(it.value())
                    .build();
            resp.addPod(Impression.newBuilder()
                    .setImpressionId(impressionId)
                    .setToken(tokens.encode(token))
                    .setCreative(Creative.newBuilder()
                            .setCreativeId(cs.id())
                            .setCampaignId(c.id())
                            .setAdvertiserId(c.advertiserId())
                            .setCategory(c.category())
                            .setDurationS(cs.durationS()))
                    .setSlot(slot)
                    .setPriceMicros(it.value())
                    .addAllTracking(TRACKING));
            served.add(new String[]{c.id(), impressionId});
            if (pacerName.isEmpty()) pacerName = pacing.pacer(c, day).kind().wire();
            budget.charge(c.id(), day, it.value());
            pacing.recordSpend(c, day, it.value());
        }
        resp.setPacer(pacerName);
        t = lap(Stage.TOKENS, t);

        // 8. Log and count, both fire-and-forget.
        for (String[] s : served) {
            caps.recordImpression(req.getViewerId(), s[0], EventIds.impression(s[1]), now);
        }
        long micros = (System.nanoTime() - start) / 1_000;
        resp.setDecisionLatencyUs((int) Math.min(Integer.MAX_VALUE, micros));
        AdResponse response = resp.build();
        DecisionRecord.Builder rec = DecisionRecord.newBuilder()
                .setResponse(response)
                .setRequest(req)
                .setCapMode(capState)
                .setSolver(solver.name())
                .setPodValueMicros(pod.value());
        if (config.logCandidates()) {
            boolean[] reached = new boolean[n];
            for (int j = 0; j < m; j++) reached[live[j]] = true;
            for (int i = 0; i < n; i++) {
                if (dropped[i] == null && !reached[i]) continue; // not targeted: omitted
                Campaign c = snap.campaign(i);
                CreativeSpec cs = c.creatives().isEmpty() ? null : c.creatives().get(0);
                rec.addCandidates(ScoredCandidate.newBuilder()
                        .setCampaignId(c.id())
                        .setCreativeId(cs == null ? "" : cs.id())
                        .setAdvertiserId(c.advertiserId())
                        .setCategory(c.category())
                        .setDurationS(cs == null ? 0 : cs.durationS())
                        .setValueMicros(cs == null ? 0 : snap.creativeValue(i, 0))
                        .setDroppedBy(dropped[i] == null ? "" : dropped[i]));
            }
        }
        DecisionRecord record = rec.build();
        log.publish(record);
        lap(Stage.LOG, t);
        return new Decision(response, record);
    }

    private long lap(Stage s, long since) {
        long now = System.nanoTime();
        timer.record(s, now - since);
        return now;
    }

    static String newImpressionId() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return Long.toHexString(r.nextLong() | Long.MIN_VALUE).substring(1) + Long.toHexString(r.nextLong() | Long.MIN_VALUE).substring(1);
    }
}
