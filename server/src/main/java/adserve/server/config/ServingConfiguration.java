package adserve.server.config;

import adserve.beacons.RedisCounters;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.DecisionEngine;
import adserve.core.engine.DecisionLog;
import adserve.core.engine.EngineConfig;
import adserve.core.engine.PacingController;
import adserve.core.pod.DpSolver;
import adserve.core.pod.ExactSolver;
import adserve.core.pod.GreedySolver;
import adserve.core.pod.PodSolver;
import adserve.core.pod.Separation;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;
import adserve.server.DecisionService;
import adserve.server.budget.BudgetSync;
import adserve.server.campaigns.CampaignCache;
import adserve.server.grpc.AdDecisionGrpcService;
import adserve.server.grpc.GrpcServer;
import adserve.server.legacy.SyncDecisionWriter;
import adserve.server.log.KafkaDecisionLog;
import adserve.server.metrics.DecisionMetrics;
import adserve.server.shed.Admission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Configuration
public class ServingConfiguration {

    @Bean(destroyMethod = "close")
    public RedisCounters redisCounters(AdServeProperties p) {
        return new RedisCounters(p.redis().uri(), p.redis().capTimeoutMs(), true, Math.max(1, p.redis().connections()));
    }

    @Bean(destroyMethod = "close")
    public KafkaDecisionLog decisionLog(AdServeProperties p, MeterRegistry registry) {
        return new KafkaDecisionLog(p.kafka().bootstrap(), p.kafka().topic(), p.kafka().bufferRecords(), registry);
    }

    @Bean
    public BudgetLedger budgetLedger() {
        return new BudgetLedger();
    }

    @Bean
    public PacingController pacingController(AdServeProperties p, BudgetLedger ledger) throws IOException {
        int slots = (int) (86_400_000L / p.pacingSlotMs());
        return PacingController.standard(p.pacingSlotMs(), new Forecasts(p.forecastFile(), slots), ledger);
    }

    @Bean
    public DecisionMetrics decisionMetrics(MeterRegistry registry) {
        return new DecisionMetrics(registry);
    }

    /** The in-process viewer segment cache for requests that arrive without segments. */
    @Bean
    public ConcurrentHashMap<String, List<String>> viewerSegments() {
        return new ConcurrentHashMap<>();
    }

    public static PodSolver solver(String name) {
        return switch (name) {
            case "greedy" -> new GreedySolver(false);
            case "greedy_swap" -> new GreedySolver(true);
            case "dp" -> new DpSolver();
            case "exact" -> new ExactSolver(200_000);
            default -> throw new IllegalArgumentException("unknown solver " + name);
        };
    }

    @Bean
    public DecisionEngine decisionEngine(AdServeProperties p, CampaignCache cache, RedisCounters counters,
                                         BudgetLedger ledger, PacingController pacing, KafkaDecisionLog log,
                                         DecisionMetrics metrics, ConcurrentHashMap<String, List<String>> viewerSegments) {
        EngineConfig cfg = EngineConfig.defaults()
                .withCapMode(p.capMode())
                .withServingRegion(p.servingRegion())
                .withRequestClock(p.useRequestClock())
                .withSeparation(Separation.ADJACENT);
        Function<String, List<String>> lookup = v -> viewerSegments.getOrDefault(v, List.of());
        DecisionLog sink = log;
        return new DecisionEngine(cfg, cache, counters, ledger, pacing, solver(p.solver()), TokenCodec.fromEnv(),
                sink, BrandSafety.defaults(), lookup, null, metrics);
    }

    @Bean
    public Admission admission(AdServeProperties p, MeterRegistry registry) {
        return new Admission(p.shedding(), registry);
    }

    @Bean
    public BudgetSync budgetSync(AdServeProperties p, RedisCounters counters, BudgetLedger ledger, CampaignCache cache) {
        return new BudgetSync(counters, ledger, cache, p.useRequestClock());
    }

    @Bean
    public DecisionService decisionService(AdServeProperties p, DecisionEngine engine, Admission admission,
                                           DecisionMetrics metrics, BudgetSync budgetSync, JdbcTemplate jdbc,
                                           MeterRegistry registry) {
        SyncDecisionWriter legacy = p.legacySyncWrite() ? new SyncDecisionWriter(jdbc) : null;
        return new DecisionService(engine, admission, legacy, metrics, budgetSync, registry);
    }

    @Bean
    public GrpcServer grpcServer(AdServeProperties p, DecisionService service) {
        return new GrpcServer(p.grpcPort(), new AdDecisionGrpcService(service), p.executor());
    }
}
