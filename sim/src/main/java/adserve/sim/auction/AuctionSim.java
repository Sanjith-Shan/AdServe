package adserve.sim.auction;

import ads.v1.AdResponse;
import ads.v1.Impression;
import adserve.core.auction.AuctionConfig;
import adserve.core.auction.PricingRule;
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
import adserve.core.model.Campaign;
import adserve.core.model.PacerKind;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.DpSolver;
import adserve.core.pod.Item;
import adserve.core.pod.Pod;
import adserve.core.pod.PodRules;
import adserve.core.pod.PodSolver;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the pricing and shading experiments share: flags, the reserve, the unconstrained campaign
 * set (unlimited budgets, unpaced) and an in-process decision engine for one pricing rule.
 */
final class AuctionSim {
    private AuctionSim() {}

    static final double MICROS_PER_YUAN = 1_000_000.0;

    /** {@code --key value} pairs. */
    static Map<String, String> flags(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].startsWith("--")) throw new IllegalArgumentException("expected --flag, got " + a[i]);
            if (i + 1 >= a.length) throw new IllegalArgumentException("missing value for " + a[i]);
            m.put(a[i].substring(2), a[++i]);
        }
        return m;
    }

    /**
     * The reserve in micros: {@code --reserve-micros} if given, else {@code reserve_micros} from
     * the auction file. Fails when neither is there, so no run silently prices against 0.
     */
    static long reserve(Map<String, String> f) throws Exception {
        if (f.containsKey("reserve-micros")) return Long.parseLong(f.get("reserve-micros"));
        Path p = Path.of(f.getOrDefault("auction", "data/work/auction.json"));
        if (!Files.exists(p)) {
            throw new IllegalStateException(p + " not found: it carries reserve_micros (written with the derived bids); "
                    + "pass --reserve-micros to override");
        }
        JsonNode n = Results.json().readTree(p.toFile());
        JsonNode r = n.get("reserve_micros");
        if (r == null || !r.canConvertToLong()) throw new IllegalStateException(p + " has no numeric reserve_micros");
        return r.asLong();
    }

    static String reserveSource(Map<String, String> f) {
        return f.containsKey("reserve-micros") ? "--reserve-micros" : f.getOrDefault("auction", "data/work/auction.json");
    }

    /** "2013-06-11" from a file named requests-20130611*.bin. */
    static String replayDay(Path requests) {
        Matcher m = Pattern.compile("requests-(\\d{4})(\\d{2})(\\d{2})").matcher(requests.getFileName().toString());
        return m.find() ? m.group(1) + "-" + m.group(2) + "-" + m.group(3) : requests.getFileName().toString();
    }

    /** Unlimited budgets and no pacing, so neither can change which pods win. */
    static List<Campaign> unconstrained(List<Campaign> cs) {
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), Long.MAX_VALUE / 4,
                    c.flightStartMs(), c.flightEndMs(), PacerKind.UNPACED, c.cap(), c.targeting(), c.creatives(), c.active()));
        }
        return out;
    }

    /** Every campaign of {@code advertiser} bids {@code 1 - shade} of its bid; the rest are unchanged. */
    static List<Campaign> shaded(List<Campaign> cs, String advertiser, double shade) {
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            long bid = c.advertiserId().equals(advertiser) ? Math.round(c.cpcBidMicros() * (1.0 - shade)) : c.cpcBidMicros();
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), bid, c.dailyBudgetMicros(),
                    c.flightStartMs(), c.flightEndMs(), c.pacer(), c.cap(), c.targeting(), c.creatives(), c.active()));
        }
        return out;
    }

    /** Frequency caps on or off; off means every counter reads zero (no campaign or ad-load cap binds). */
    static CapStore caps(boolean on) {
        if (on) return new CompactCapStore();
        return new CapStore() {
            public CapCounts fetch(String viewerId, List<String> ids, long nowMs) {
                return new CapCounts(new long[ids.size()], new long[ids.size()], 0, true);
            }

            public void recordImpression(String viewerId, String campaignId, String eventId, long tsMs) {}
        };
    }

    /**
     * The same counters as {@link InMemoryCapStore} (per viewer: per campaign per day and per
     * week, and ads per hour, on the same {@link Windows}), packed into a few longs per viewer
     * instead of three string keys and an event-id string per impression. That store needs about
     * 1 KB per impression, which over a whole replay day (5 million impressions per engine, two
     * engines) filled a 6 GB heap. It does not deduplicate event ids: in process, the engine
     * records each impression exactly once, with a fresh id, so the idempotent store never drops
     * one and the counts are identical. One engine, one thread.
     */
    static final class CompactCapStore implements CapStore {
        private static final long DAY = 0, WEEK = 1, HOUR = 2;
        private final Map<String, Integer> campaignIndex = new HashMap<>();
        private final Map<String, Viewer> viewers = new HashMap<>();

        private static final class Viewer {
            long[] keys = new long[4];
            int[] counts = new int[4];
            int n;

            int get(long k) {
                for (int i = 0; i < n; i++) if (keys[i] == k) return counts[i];
                return 0;
            }

            void incr(long k) {
                for (int i = 0; i < n; i++) {
                    if (keys[i] == k) {
                        counts[i]++;
                        return;
                    }
                }
                if (n == keys.length) {
                    keys = java.util.Arrays.copyOf(keys, n * 2);
                    counts = java.util.Arrays.copyOf(counts, n * 2);
                }
                keys[n] = k;
                counts[n++] = 1;
            }
        }

        /** kind in the top 2 bits, campaign index in the next 20, window number in the low 42. */
        private static long key(long kind, int campaign, long window) {
            if (window < 0 || window >= (1L << 42)) throw new IllegalArgumentException("window out of range: " + window);
            return kind << 62 | (long) campaign << 42 | window;
        }

        private int campaign(String id) {
            Integer i = campaignIndex.get(id);
            if (i == null) {
                i = campaignIndex.size();
                if (i >= (1 << 20)) throw new IllegalStateException("too many campaigns");
                campaignIndex.put(id, i);
            }
            return i;
        }

        public CapCounts fetch(String viewerId, List<String> ids, long nowMs) {
            int n = ids.size();
            long[] day = new long[n], week = new long[n];
            Viewer v = viewers.get(viewerId);
            if (v == null) return new CapCounts(day, week, 0, true);
            long d = Windows.day(nowMs), w = Windows.week(nowMs);
            for (int i = 0; i < n; i++) {
                int c = campaign(ids.get(i));
                day[i] = v.get(key(DAY, c, d));
                week[i] = v.get(key(WEEK, c, w));
            }
            return new CapCounts(day, week, v.get(key(HOUR, 0, Windows.hour(nowMs))), true);
        }

        public void recordImpression(String viewerId, String campaignId, String eventId, long tsMs) {
            Viewer v = viewers.computeIfAbsent(viewerId, k -> new Viewer());
            int c = campaign(campaignId);
            v.incr(key(DAY, c, Windows.day(tsMs)));
            v.incr(key(WEEK, c, Windows.week(tsMs)));
            v.incr(key(HOUR, 0, Windows.hour(tsMs)));
        }
    }

    static DecisionEngine engine(List<Campaign> cs, PricingRule rule, long reserveMicros, boolean capsOn) {
        return engine(cs, rule, reserveMicros, capsOn, new DpSolver());
    }

    static DecisionEngine engine(List<Campaign> cs, PricingRule rule, long reserveMicros, boolean capsOn, PodSolver solver) {
        CampaignSnapshot snap = new CampaignSnapshot(1, cs);
        BudgetLedger ledger = new BudgetLedger();
        PacingController pacing = PacingController.standard(60_000L, c -> PacingPlan.flat(1440), ledger);
        EngineConfig cfg = EngineConfig.defaults().withLogCandidates(false)
                .withAuction(AuctionConfig.defaults().withPricing(rule).withReserve(reserveMicros));
        TokenCodec tokens = new TokenCodec("auction-experiment-key-0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        SplittableRandom rnd = new SplittableRandom(20130611L);
        return new DecisionEngine(cfg, SnapshotSource.fixed(snap), caps(capsOn), ledger, pacing, solver,
                tokens, DecisionLog.NONE, BrandSafety.defaults(), v -> List.of(), rnd::nextDouble, StageTimer.NONE);
    }

    /**
     * The engine's own DP solver, keeping the candidate list, rules and pod of its latest call, so
     * a harness can re-price exactly the auction the engine ran. One engine, one thread.
     */
    static final class RecordingSolver implements PodSolver {
        final DpSolver dp = new DpSolver();
        List<Item> items = List.of();
        PodRules rules;
        Pod pod = Pod.EMPTY;

        public String name() {
            return dp.name();
        }

        public Pod solve(List<Item> candidates, PodRules r) {
            items = candidates;
            rules = r;
            pod = dp.solve(candidates, r);
            return pod;
        }

        void clear() {
            items = List.of();
            rules = null;
            pod = Pod.EMPTY;
        }
    }

    /** True when both responses hold the same creatives in the same slots at the same bids and scores. */
    static boolean samePod(AdResponse a, AdResponse b) {
        if (a.getPodCount() != b.getPodCount()) return false;
        for (int i = 0; i < a.getPodCount(); i++) {
            Impression x = a.getPod(i), y = b.getPod(i);
            if (!x.getCreative().getCreativeId().equals(y.getCreative().getCreativeId())
                    || !x.getCreative().getCampaignId().equals(y.getCreative().getCampaignId())
                    || x.getBidMicros() != y.getBidMicros() || x.getScoreMicros() != y.getScoreMicros()) {
                return false;
            }
        }
        return true;
    }

    static double yuan(long micros) {
        return Math.round(micros / MICROS_PER_YUAN * 100) / 100.0;
    }

    static double yuan(double micros) {
        return Math.round(micros / MICROS_PER_YUAN * 100) / 100.0;
    }
}
