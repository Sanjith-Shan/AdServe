package adserve.sim.load;

import ads.v1.AdRequest;
import ads.v1.Priority;
import adserve.core.io.RequestFiles;
import adserve.sim.Machine;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Experiment 1 (and the load half of 5 and 6): a warm-up at a steady rate, then synchronized live
 * breaks of {@code n} requests in 2 seconds, repeated. Requests are real ad-break contexts from the
 * replay day (geo, device, segments, genre, break length) with fresh viewer ids per run, all
 * stamped at one break instant, so every repeat is a new audience hitting the same break.
 *
 * <p>Args: label sizes(comma) repeats liveShare [target] [warmupRate] [warmupSeconds] [file] [results].
 * liveShare is the fraction of requests marked LIVE (the rest VOD).
 */
public final class BurstExperiment {
    public static final long BREAK_TS = 1_370_980_800_000L; // 2013-06-11 20:00:00 UTC

    public static void main(String[] a) throws Exception {
        String label = a[0];
        int[] sizes = java.util.Arrays.stream(a[1].split(",")).mapToInt(Integer::parseInt).toArray();
        int repeats = Integer.parseInt(a[2]);
        double liveShare = Double.parseDouble(a[3]);
        String target = a.length > 4 ? a[4] : "localhost:29100";
        double warmRate = a.length > 5 ? Double.parseDouble(a[5]) : 3000;
        int warmSeconds = a.length > 6 ? Integer.parseInt(a[6]) : 30;
        Path file = Path.of(a.length > 7 ? a[7] : "data/work/requests-20130611.bin");
        String resultsFile = a.length > 8 ? a[8] : "exp1_burst.jsonl";
        String note = System.getenv().getOrDefault("RUN_NOTE", "");

        int maxN = java.util.Arrays.stream(sizes).max().orElse(0);
        List<AdRequest> base = RequestFiles.readAll(file, Math.max(maxN, (int) (warmRate * warmSeconds)) + 1000);
        try (LoadGen gen = new LoadGen(target, 8, 5_000)) {
            // Warm-up: VOD at a steady rate, not recorded.
            String w = "w" + UUID.randomUUID().toString().substring(0, 6);
            int wn = (int) (warmRate * warmSeconds);
            gen.run(wn, LoadGen.constantRate(warmRate),
                    i -> stamp(base.get(i % base.size()), w, i, Priority.VOD, BREAK_TS - 600_000L + i), new LoadGen.Tally(), new LoadGen.Tally());
            Thread.sleep(3000);
            for (int n : sizes) {
                for (int rep = 1; rep <= repeats; rep++) {
                    String run = "b" + UUID.randomUUID().toString().substring(0, 6);
                    long liveEvery = liveShare <= 0 ? Long.MAX_VALUE : Math.round(1 / liveShare);
                    LoadGen.Tally live = new LoadGen.Tally(), vod = new LoadGen.Tally();
                    String load = Machine.loadAvg();
                    gen.run(n, Schedules.liveBreak(n, 2.0, 0.4), i -> {
                        Priority p = liveShare >= 1 || (liveShare > 0 && i % liveEvery == 0) ? Priority.LIVE : Priority.VOD;
                        return stamp(base.get(i % base.size()), run, i, p, BREAK_TS + (i % 2000));
                    }, live, vod);
                    ObjectNode out = Results.line("exp1_burst");
                    out.put("label", label);
                    if (!note.isEmpty()) out.put("note", note);
                    out.put("load_avg_before_burst", load);
                    out.put("burst_requests", n);
                    out.put("burst_window_s", 2.0);
                    out.put("arrival_shape", "gamma k=2, theta=0.4 s, truncated at 2 s");
                    out.put("offered_mean_per_s", n / 2.0);
                    out.put("offered_peak_per_s", Schedules.liveBreakPeakRate(n, 2.0, 0.4));
                    out.put("repeat", rep);
                    out.put("live_share", liveShare);
                    out.put("warmup", warmSeconds + " s at " + (int) warmRate + "/s before the first burst");
                    out.put("traffic", "real ad-break contexts from iPinYou 2013-06-11, fresh simulated viewers per burst");
                    out.put("load_generator", "same machine, separate JVM, open loop, 8 gRPC channels");
                    LoadGen.summarize(out, "live", live, 2.0);
                    LoadGen.summarize(out, "vod", vod, 2.0);
                    Results.append(resultsFile, out);
                    System.out.printf("%s n=%d rep=%d live ok=%d p50=%.2f p99=%.2f | vod ok=%d shed=%d p99=%.2f%n", label, n, rep,
                            live.ok.sum(), out.get("live").get("p50_ms").asDouble(), out.get("live").get("p99_ms").asDouble(),
                            vod.ok.sum(), vod.shed.sum(), out.get("vod").get("p99_ms").asDouble());
                    Thread.sleep(4000);
                }
            }
        }
    }

    static AdRequest stamp(AdRequest r, String run, int i, Priority p, long ts) {
        return r.toBuilder().setRequestId(run + "-" + i).setViewerId(run + "-" + r.getViewerId())
                .setPriority(p).setTsMs(ts).build();
    }
}
