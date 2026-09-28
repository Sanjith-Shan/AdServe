package adserve.core.engine;

import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Creative;
import ads.v1.DecisionRecord;
import ads.v1.EventType;
import ads.v1.Impression;
import ads.v1.ScoredCandidate;
import ads.v1.Token;
import adserve.core.auction.Auction;
import adserve.core.auction.AuctionConfig;
import adserve.core.auction.QualitySignal;
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

    private volatile QualitySignal quality = QualitySignal.NONE;

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

    /** Where the optional quality term reads skip rates; only consulted when its weight is above 0. */
    public void setQualitySignal(QualitySignal q) {
        this.quality = q == null ? QualitySignal.NONE : q;
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

        // 4. Frequency cap: one round trip for every surviving capped campaign plus the viewer's
        // hourly ad load. Uncapped campaigns need no counter.
        String capState = "ok";
        int[] capRemaining = new int[n];
        int hourRemaining = config.maxAdsPerViewerHour();
        if (m > 0) {
            List<String> ids = new ArrayList<>(m);
            int[] slotOf = new int[m];
            for (int j = 0; j < m; j++) {
                Campaign c = snap.campaign(live[j]);
                if (c.cap().capped()) {
                    slotOf[j] = ids.size();
                    ids.add(c.id());
                } else {
                    slotOf[j] = -1;
                }
            }
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
                if (slotOf[j] < 0) {
                    rem = Integer.MAX_VALUE;
                } else if (counts.known()) {
                    rem = c.cap().remaining(counts.day()[slotOf[j]], counts.week()[slotOf[j]]);
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

        // 6. The auction and pod assembly. Every creative of every surviving campaign bids its
        // value (cpc bid x click rate x duration factor, times the pacer's bid multiplier); a bid
        // below the reserve does not enter. The solver maximises total auction score, then every
        // slot is priced (second price by default, see Auction).
        final AuctionConfig auction = config.auction();
        final double qualityWeight = auction.qualityWeight();
        List<Item> items = new ArrayList<>();
        List<int[]> refs = new ArrayList<>();
        long[] bids = new long[16];
        for (int j = 0; j < m; j++) {
            int i = live[j];
            Campaign c = snap.campaign(i);
            long remainingBudget = c.dailyBudgetMicros() - budget.spent(c.id(), day);
            double mult = pacing.bidMultiplier(c, day);
            boolean entered = false;
            for (int x = 0; x < c.creatives().size(); x++) {
                long v = snap.creativeValue(i, x);
                if (mult != 1.0) v = Math.round(v * mult);
                if (v <= 0 || v > remainingBudget || v < auction.reserveMicros()) continue;
                long score = qualityWeight > 0
                        ? Auction.score(v, quality.skipRate(c.creatives().get(x).id()), qualityWeight) : v;
                if (score <= 0) continue;
                int ref = refs.size();
                if (ref == bids.length) bids = java.util.Arrays.copyOf(bids, ref * 2);
                bids[ref] = v;
                items.add(new Item(ref, i, snap.advertiser(i), snap.category(i),
                        c.creatives().get(x).durationS(), score));
                refs.add(new int[]{i, x});
                entered = true;
            }
            if (!entered && auction.reserveMicros() > 0) dropped[i] = "reserve";
        }
        PodRules rules = new PodRules(req.getBreakLengthS(), config.minAds(),
                Math.min(config.maxAds(), hourRemaining), config.separation());
        Pod pod = items.isEmpty() || rules.maxAds() == 0 ? Pod.EMPTY : solver.solve(items, rules);
        if (PodValidator.violation(pod, items, rules) != null) {
            invalidPods.increment();
            pod = Pod.EMPTY;
        }
        Auction.Clearing[] cleared = pod.size() == 0 ? new Auction.Clearing[0]
                : Auction.price(pod, items, bids, rules, auction);
        t = lap(Stage.SELECTION, t);

        // 7. Tokens and response.
        AdResponse.Builder resp = AdResponse.newBuilder()
                .setRequestId(req.getRequestId())
                .setViewerId(req.getViewerId())
                .setServingRegion(config.servingRegion())
                .setDecidedTsMs(now)
                .setCandidatesConsidered(matched);
        String pacerName = "";
        String[] servedCampaigns = new String[pod.size()];
        String[] servedEvents = new String[pod.size()];
        long podCleared = 0;
        for (int slot = 0; slot < pod.size(); slot++) {
            Item it = pod.items().get(slot);
            Auction.Clearing clearing = cleared[slot];
            long price = clearing.priceMicros();
            podCleared += price;
            String pricedAgainst = clearing.rivalRef() < 0 ? ""
                    : snap.campaign(refs.get(clearing.rivalRef())[0]).id();
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
                    .setPriceMicros(price)
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
                    .setPriceMicros(price)
                    .setBidMicros(clearing.bidMicros())
                    .setScoreMicros(clearing.scoreMicros())
                    .setPricedAgainst(pricedAgainst)
                    .addAllTracking(TRACKING));
            servedCampaigns[slot] = c.id();
            servedEvents[slot] = EventIds.impression(impressionId);
            if (pacerName.isEmpty()) pacerName = pacing.pacer(c, day).kind().wire();
            budget.charge(c.id(), day, price);
            pacing.recordSpend(c, day, price);
        }
        resp.setPacer(pacerName);
        t = lap(Stage.TOKENS, t);

        // 8. Log and count, both fire-and-forget.
        if (pod.size() > 0) caps.recordPod(req.getViewerId(), servedCampaigns, servedEvents, now);
        long micros = (System.nanoTime() - start) / 1_000;
        resp.setDecisionLatencyUs((int) Math.min(Integer.MAX_VALUE, micros));
        AdResponse response = resp.build();
        DecisionRecord.Builder rec = DecisionRecord.newBuilder()
                .setResponse(response)
                .setRequest(req)
                .setCapMode(capState)
                .setSolver(solver.name())
                .setPodValueMicros(pod.value())
                .setPricing(auction.pricing().wire())
                .setPodClearedMicros(podCleared)
                .setReserveMicros(auction.reserveMicros());
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
