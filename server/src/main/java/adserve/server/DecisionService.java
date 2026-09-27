package adserve.server;

import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Priority;
import adserve.core.engine.Decision;
import adserve.core.engine.DecisionEngine;
import adserve.server.budget.BudgetSync;
import adserve.server.legacy.SyncDecisionWriter;
import adserve.server.metrics.DecisionMetrics;
import adserve.server.shed.Admission;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * What both the gRPC service and the REST facade call: admission, the engine, the legacy
 * synchronous write when that baseline is switched on, and the whole-decision timer.
 */
public class DecisionService {
    private final DecisionEngine engine;
    private final Admission admission;
    private final SyncDecisionWriter legacy;
    private final DecisionMetrics metrics;
    private final BudgetSync budgetSync;
    private final Counter legacyFailures;

    public DecisionService(DecisionEngine engine, Admission admission, SyncDecisionWriter legacy,
                           DecisionMetrics metrics, BudgetSync budgetSync, MeterRegistry registry) {
        this.engine = engine;
        this.admission = admission;
        this.legacy = legacy;
        this.metrics = metrics;
        this.budgetSync = budgetSync;
        this.legacyFailures = Counter.builder("adserve.legacy.write.failures").register(registry);
    }

    public sealed interface Result permits Served, Shed, Failed {}

    public record Served(AdResponse response) implements Result {}

    public record Shed(long retryAfterMs) implements Result {}

    public record Failed(String reason) implements Result {}

    public Result decide(AdRequest req) {
        long t0 = System.nanoTime();
        boolean live = req.getPriority() == Priority.LIVE;
        if (admission.tryAcquire(req.getPriority()) == Admission.Outcome.SHED) {
            return new Shed(admission.retryAfterMs());
        }
        try {
            Decision d = engine.decide(req);
            if (budgetSync != null) budgetSync.observe(d.response().getDecidedTsMs());
            if (legacy != null) {
                try {
                    legacy.write(d.record());
                } catch (RuntimeException e) {
                    legacyFailures.increment();
                    return new Failed("decision store write failed: " + e.getClass().getSimpleName());
                }
            }
            long nanos = System.nanoTime() - t0;
            metrics.decision(live, nanos);
            return new Served(d.response());
        } finally {
            admission.release();
        }
    }

    public DecisionEngine engine() {
        return engine;
    }
}
