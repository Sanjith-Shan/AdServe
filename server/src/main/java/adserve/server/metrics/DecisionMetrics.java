package adserve.server.metrics;

import adserve.core.engine.Stage;
import adserve.core.engine.StageTimer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Per-stage timers for the latency budget, plus the whole-decision timer, all in Prometheus. */
public final class DecisionMetrics implements StageTimer {
    private final Map<Stage, Timer> stages = new EnumMap<>(Stage.class);
    private final Timer live;
    private final Timer vod;

    public DecisionMetrics(MeterRegistry registry) {
        for (Stage s : Stage.values()) {
            stages.put(s, Timer.builder("adserve.stage").tag("stage", s.wire())
                    .publishPercentiles(0.5, 0.99).register(registry));
        }
        live = Timer.builder("adserve.decision").tag("priority", "live").publishPercentileHistogram()
                .publishPercentiles(0.5, 0.99, 0.999).register(registry);
        vod = Timer.builder("adserve.decision").tag("priority", "vod").publishPercentileHistogram()
                .publishPercentiles(0.5, 0.99, 0.999).register(registry);
    }

    @Override
    public void record(Stage stage, long nanos) {
        stages.get(stage).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void decision(boolean isLive, long nanos) {
        (isLive ? live : vod).record(nanos, TimeUnit.NANOSECONDS);
    }
}
