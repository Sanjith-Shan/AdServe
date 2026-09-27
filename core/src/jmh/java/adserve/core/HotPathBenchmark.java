package adserve.core;

import ads.v1.AdRequest;
import ads.v1.Priority;
import ads.v1.Region;
import ads.v1.Token;
import adserve.core.caps.InMemoryCapStore;
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
import adserve.core.model.PacerKind;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.DpSolver;
import adserve.core.pod.ExactSolver;
import adserve.core.pod.GreedySolver;
import adserve.core.pod.Item;
import adserve.core.pod.PodRules;
import adserve.core.pod.Separation;
import adserve.core.policy.BrandSafety;
import adserve.core.targeting.RequestContext;
import adserve.core.token.TokenCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Method-level costs on the decision path, on the real campaigns and the first 2,000 real ad
 * breaks (data/sample, so the benchmark runs from a clean checkout). Budgets are unlimited and
 * pacing is off in the full-decision benchmark so every iteration does the full work.
 */
@State(Scope.Benchmark)
public class HotPathBenchmark {
    CampaignSnapshot snap;
    List<AdRequest> requests;
    RequestContext[] contexts;
    List<List<Item>> breaks;
    TokenCodec tokens;
    Token token;
    DecisionEngine engine;
    int i;

    static Path file(String name) {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path f = p.resolve("data/sample").resolve(name);
            if (Files.exists(f)) return f;
        }
        throw new IllegalStateException("data/sample/" + name + " not found");
    }

    @Setup(Level.Trial)
    public void setup() throws Exception {
        List<Campaign> cs = new ArrayList<>();
        for (Campaign c : CampaignFiles.read(file("campaigns.json"))) {
            cs.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), Long.MAX_VALUE / 4,
                    0, Long.MAX_VALUE, PacerKind.UNPACED, c.cap(), c.targeting(), c.creatives(), true));
        }
        snap = new CampaignSnapshot(1, cs);
        requests = RequestFiles.readAll(file("requests-20130611-first20k.bin"), 2000);
        contexts = new RequestContext[requests.size()];
        breaks = new ArrayList<>();
        BrandSafety bs = BrandSafety.defaults();
        for (int k = 0; k < requests.size(); k++) {
            AdRequest r = requests.get(k);
            contexts[k] = snap.targeting().context(r.getGeo(), r.getDevice(), r.getSegmentsList(), r.getGenre());
            List<Item> items = new ArrayList<>();
            for (int c = 0; c < snap.size(); c++) {
                if (!snap.targeting().matches(c, contexts[k]) || !bs.allowed(snap.campaign(c).category(), r.getGenre())) continue;
                for (int x = 0; x < snap.campaign(c).creatives().size(); x++) {
                    items.add(new Item(items.size(), c, snap.advertiser(c), snap.category(c),
                            snap.campaign(c).creatives().get(x).durationS(), snap.creativeValue(c, x)));
                }
            }
            breaks.add(items);
        }
        tokens = new TokenCodec("benchmark-key-0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
        token = Token.newBuilder().setImpressionId("0123456789abcdef0123456789ab").setCampaignId("c1458-2abc9eaf")
                .setCreativeId("2abc9eaf57d17a96195af3f63c45dc72").setServingRegion(Region.US_EAST)
                .setIssuedTsMs(1_370_950_000_000L).setViewerId("trqRTuSoXQuIXQc_5SqfNX").setPriceMicros(812).build();
        BudgetLedger ledger = new BudgetLedger();
        engine = new DecisionEngine(EngineConfig.defaults(), SnapshotSource.fixed(snap), new InMemoryCapStore(true), ledger,
                PacingController.standard(60_000, c -> PacingPlan.flat(1440), ledger), new DpSolver(), tokens,
                DecisionLog.NONE, bs, v -> List.of(), () -> 0.0, StageTimer.NONE);
    }

    int next() {
        i = (i + 1) % requests.size();
        return i;
    }

    /** All 55 compiled predicates against one real request. */
    @Benchmark
    public int targetingAllCampaigns() {
        RequestContext ctx = contexts[next()];
        int m = 0;
        for (int c = 0; c < snap.size(); c++) if (snap.targeting().matches(c, ctx)) m++;
        return m;
    }

    @Benchmark
    public Object podDp() {
        int k = next();
        return new DpSolver().solve(breaks.get(k), new PodRules(requests.get(k).getBreakLengthS(), 1, 6, Separation.ADJACENT));
    }

    @Benchmark
    public Object podGreedy() {
        int k = next();
        return new GreedySolver(false).solve(breaks.get(k), new PodRules(requests.get(k).getBreakLengthS(), 1, 6, Separation.ADJACENT));
    }

    @Benchmark
    public Object podExact() {
        int k = next();
        return new ExactSolver().solve(breaks.get(k), new PodRules(requests.get(k).getBreakLengthS(), 1, 6, Separation.ADJACENT));
    }

    @Benchmark
    public String tokenSign() {
        return tokens.encode(token);
    }

    /** One whole decision with in-memory counters and no log: the CPU cost of the path. */
    @Benchmark
    public void fullDecision(Blackhole bh) {
        AdRequest r = requests.get(next());
        bh.consume(engine.decide(r.toBuilder().setViewerId(r.getViewerId() + "-" + System.nanoTime())
                .setPriority(Priority.LIVE).build()));
    }
}
