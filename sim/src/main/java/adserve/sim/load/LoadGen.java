package adserve.sim.load;

import ads.v1.AdDecisionGrpc;
import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.Priority;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongUnaryOperator;

/**
 * Open-loop gRPC load generator. Request i has an intended send time from the schedule; the
 * sender issues it at that time whether or not earlier requests have returned, and latency is
 * measured from the intended time, so a stalled server shows up as latency instead of as a
 * politely slower client (no coordinated omission). Runs in its own JVM on the same machine as the
 * server, and every result says so.
 */
public final class LoadGen implements AutoCloseable {

    /** Per-priority outcome counts and latency histograms (microseconds, from intended send time). */
    public static final class Tally {
        public final Recorder latency = new Recorder(3_600_000_000L, 3);
        public final LongAdder ok = new LongAdder();
        public final LongAdder shed = new LongAdder();
        public final LongAdder unavailable = new LongAdder();
        public final LongAdder deadline = new LongAdder();
        public final LongAdder other = new LongAdder();
        public final LongAdder podAds = new LongAdder();
        public final LongAdder emptyPods = new LongAdder();
        Histogram total;

        long sent() {
            return ok.sum() + shed.sum() + unavailable.sum() + deadline.sum() + other.sum();
        }
    }

    private final ManagedChannel[] channels;
    private final AdDecisionGrpc.AdDecisionStub[] stubs;
    private final long deadlineMs;

    public LoadGen(String target, int channelCount, long deadlineMs) {
        channels = new ManagedChannel[channelCount];
        stubs = new AdDecisionGrpc.AdDecisionStub[channelCount];
        for (int i = 0; i < channelCount; i++) {
            channels[i] = NettyChannelBuilder.forTarget(target).usePlaintext()
                    .flowControlWindow(4 << 20).build();
            stubs[i] = AdDecisionGrpc.newStub(channels[i]);
        }
        this.deadlineMs = deadlineMs;
    }

    /**
     * Sends {@code n} requests; request i is intended at {@code start + scheduleNs(i)}.
     * {@code source} returns request i (already stamped with priority and ids).
     */
    public void run(int n, LongUnaryOperator scheduleNs, java.util.function.IntFunction<AdRequest> source,
                    Tally live, Tally vod) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(n);
        long start = System.nanoTime() + 50_000_000L;
        for (int i = 0; i < n; i++) {
            long intended = start + scheduleNs.applyAsLong(i);
            long wait;
            while ((wait = intended - System.nanoTime()) > 0) {
                if (wait > 200_000) LockSupport.parkNanos(wait - 100_000);
                else Thread.onSpinWait();
            }
            AdRequest req = source.apply(i);
            Tally t = req.getPriority() == Priority.LIVE ? live : vod;
            stubs[i % stubs.length].withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .decide(req, new StreamObserver<>() {
                        @Override
                        public void onNext(AdResponse r) {
                            t.podAds.add(r.getPodCount());
                            if (r.getPodCount() == 0) t.emptyPods.increment();
                        }

                        @Override
                        public void onError(Throwable e) {
                            record(t, intended);
                            Status.Code c = e instanceof StatusRuntimeException s ? s.getStatus().getCode() : Status.Code.UNKNOWN;
                            switch (c) {
                                case RESOURCE_EXHAUSTED -> t.shed.increment();
                                case UNAVAILABLE -> t.unavailable.increment();
                                case DEADLINE_EXCEEDED -> t.deadline.increment();
                                default -> t.other.increment();
                            }
                            done.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            record(t, intended);
                            t.ok.increment();
                            done.countDown();
                        }
                    });
        }
        done.await(deadlineMs + 30_000, TimeUnit.MILLISECONDS);
    }

    private static void record(Tally t, long intendedNs) {
        long us = Math.max(1, (System.nanoTime() - intendedNs) / 1000);
        t.latency.recordValue(Math.min(us, 3_600_000_000L));
    }

    /** Only successful responses carry a meaningful decision latency; errors are counted separately. */
    public static void summarize(ObjectNode out, String name, Tally t, double seconds) {
        Histogram h = t.latency.getIntervalHistogram();
        ObjectNode n = out.putObject(name);
        long sent = t.sent();
        n.put("sent", sent);
        n.put("ok", t.ok.sum());
        n.put("shed", t.shed.sum());
        n.put("unavailable", t.unavailable.sum());
        n.put("deadline_exceeded", t.deadline.sum());
        n.put("other_errors", t.other.sum());
        n.put("error_rate", sent == 0 ? 0 : (double) (sent - t.ok.sum() - t.shed.sum()) / sent);
        n.put("shed_rate", sent == 0 ? 0 : (double) t.shed.sum() / sent);
        n.put("achieved_ok_per_s", t.ok.sum() / seconds);
        n.put("p50_ms", h.getValueAtPercentile(50) / 1000.0);
        n.put("p90_ms", h.getValueAtPercentile(90) / 1000.0);
        n.put("p99_ms", h.getValueAtPercentile(99) / 1000.0);
        n.put("p999_ms", h.getValueAtPercentile(99.9) / 1000.0);
        n.put("max_ms", h.getMaxValue() / 1000.0);
        n.put("mean_ads_per_pod", t.ok.sum() == 0 ? 0 : (double) t.podAds.sum() / t.ok.sum());
        n.put("empty_pods", t.emptyPods.sum());
    }

    @Override
    public void close() throws InterruptedException {
        for (ManagedChannel c : channels) c.shutdown();
        for (ManagedChannel c : channels) c.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Evenly spaced schedule at {@code rate} per second. */
    public static LongUnaryOperator constantRate(double rate) {
        double gap = 1e9 / rate;
        return i -> (long) (i * gap);
    }
}
