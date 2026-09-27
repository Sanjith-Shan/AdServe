package adserve.billing;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.metrics.Counter;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * The Deduplicate stage, keyed by the stable event id: the first copy passes, later copies inside
 * the TTL are dropped. Best-effort by design (state expires, and at-least-once replays after a
 * failure can re-emit), which is why the event id also travels to the sink and the billing table
 * dedupes again on its primary key.
 */
public final class Deduplicate extends KeyedProcessFunction<String, BillingRow, BillingRow> {
    private final Duration ttl;
    private transient ValueState<Boolean> seen;
    private transient Counter dropped;

    public Deduplicate(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public void open(OpenContext ctx) {
        ValueStateDescriptor<Boolean> d = new ValueStateDescriptor<>("seen", Boolean.class);
        d.enableTimeToLive(StateTtlConfig.newBuilder(ttl).build());
        seen = getRuntimeContext().getState(d);
        dropped = getRuntimeContext().getMetricGroup().counter("duplicatesDropped");
    }

    @Override
    public void processElement(BillingRow r, Context ctx, Collector<BillingRow> out) throws Exception {
        if (seen.value() != null) {
            dropped.inc();
            return;
        }
        seen.update(true);
        out.collect(r);
    }
}
