package adserve.billing;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Join stage, keyed by impression id: the decision's context meets the device's beacons.
 * Either side may arrive first; beacons that beat their decision wait in state. After the join
 * window a context is dropped by TTL, and beacons still waiting are counted as unmatched (the
 * input a periodic recovery job would reprocess).
 */
public final class JoinBeacons extends KeyedCoProcessFunction<String, ImpressionContext, BeaconEvent, BillingRow> {
    private final Duration window;
    private transient ValueState<ImpressionContext> context;
    private transient ListState<BeaconEvent> waiting;
    private transient Counter unmatched;

    public JoinBeacons(Duration window) {
        this.window = window;
    }

    @Override
    public void open(OpenContext ctx) {
        StateTtlConfig ttl = StateTtlConfig.newBuilder(window).build();
        ValueStateDescriptor<ImpressionContext> c = new ValueStateDescriptor<>("context", ImpressionContext.class);
        c.enableTimeToLive(ttl);
        context = getRuntimeContext().getState(c);
        waiting = getRuntimeContext().getListState(new ListStateDescriptor<>("waiting", BeaconEvent.class));
        unmatched = getRuntimeContext().getMetricGroup().counter("unmatchedBeacons");
    }

    @Override
    public void processElement1(ImpressionContext c, Context ctx, Collector<BillingRow> out) throws Exception {
        context.update(c);
        List<BeaconEvent> held = new ArrayList<>();
        for (BeaconEvent b : waiting.get()) held.add(b);
        for (BeaconEvent b : held) out.collect(BillingRow.of(c, b));
        waiting.clear();
    }

    @Override
    public void processElement2(BeaconEvent b, Context ctx, Collector<BillingRow> out) throws Exception {
        ImpressionContext c = context.value();
        if (c != null) {
            out.collect(BillingRow.of(c, b));
            return;
        }
        waiting.add(b);
        ctx.timerService().registerProcessingTimeTimer(ctx.timerService().currentProcessingTime() + window.toMillis());
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<BillingRow> out) throws Exception {
        int n = 0;
        for (BeaconEvent ignored : waiting.get()) n++;
        if (n > 0 && context.value() == null) {
            unmatched.inc(n);
            waiting.clear();
        }
    }
}
