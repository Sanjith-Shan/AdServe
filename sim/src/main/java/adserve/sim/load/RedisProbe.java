package adserve.sim.load;

import adserve.beacons.RedisCounters;
import org.HdrHistogram.Recorder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Diagnostic: one counter fetch per task at a fixed rate, on virtual or platform threads. */
public final class RedisProbe {
    public static void main(String[] a) throws Exception {
        String mode = a.length > 0 ? a[0] : "virtual";
        double rate = a.length > 1 ? Double.parseDouble(a[1]) : 2000;
        int seconds = a.length > 2 ? Integer.parseInt(a[2]) : 5;
        try (RedisCounters rc = new RedisCounters("redis://localhost:26379", 1000, true)) {
            ExecutorService ex = mode.equals("virtual") ? Executors.newVirtualThreadPerTaskExecutor()
                    : Executors.newFixedThreadPool(64);
            Recorder rec = new Recorder(3);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 10; i++) ids.add("c" + i);
            long n = (long) (rate * seconds);
            long start = System.nanoTime();
            for (long i = 0; i < n; i++) {
                long intended = start + (long) (i * 1e9 / rate);
                while (System.nanoTime() < intended) LockSupport.parkNanos(50_000);
                String viewer = "v" + i;
                ex.submit(() -> {
                    rc.fetch(viewer, ids, 1_370_950_000_000L);
                    rec.recordValue((System.nanoTime() - intended) / 1000);
                });
            }
            ex.shutdown();
            ex.awaitTermination(30, TimeUnit.SECONDS);
            var h = rec.getIntervalHistogram();
            System.out.printf("%s rate=%.0f p50=%dus p99=%dus max=%dus%n", mode, rate,
                    h.getValueAtPercentile(50), h.getValueAtPercentile(99), h.getMaxValue());
        }
    }
}
