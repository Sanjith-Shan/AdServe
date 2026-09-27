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
 * Experiment 5's load: sustained offered load at multiples of measured capacity, a fixed share
 * of it LIVE, after a warm-up. Args: label capacity multiples(comma) seconds repeats liveShare [target].
 */
public final class OverloadExperiment {
    public static void main(String[] a) throws Exception {
        String label = a[0];
        double capacity = Double.parseDouble(a[1]);
        double[] multiples = java.util.Arrays.stream(a[2].split(",")).mapToDouble(Double::parseDouble).toArray();
        int seconds = Integer.parseInt(a[3]);
        int repeats = Integer.parseInt(a[4]);
        double liveShare = Double.parseDouble(a[5]);
        String target = a.length > 6 ? a[6] : "localhost:29100";
        double maxRate = capacity * java.util.Arrays.stream(multiples).max().orElse(1);
        List<AdRequest> base = RequestFiles.readAll(Path.of("data/work/requests-20130611.bin"), (int) Math.min(400_000, maxRate * seconds + 1000));
        long liveEvery = Math.round(1 / liveShare);
        try (LoadGen gen = new LoadGen(target, 8, 5_000)) {
            String w = "w" + UUID.randomUUID().toString().substring(0, 6);
            int wn = (int) (capacity * 0.5 * 20);
            gen.run(wn, LoadGen.constantRate(capacity * 0.5), i -> BurstExperiment.stamp(base.get(i % base.size()), w, i,
                    Priority.VOD, BurstExperiment.BREAK_TS - 600_000L + i), new LoadGen.Tally(), new LoadGen.Tally());
            Thread.sleep(3000);
            for (double m : multiples) {
                for (int rep = 1; rep <= repeats; rep++) {
                    double rate = capacity * m;
                    int n = (int) (rate * seconds);
                    String run = "o" + UUID.randomUUID().toString().substring(0, 6);
                    LoadGen.Tally live = new LoadGen.Tally(), vod = new LoadGen.Tally();
                    String load = Machine.loadAvg();
                    gen.run(n, LoadGen.constantRate(rate), i -> BurstExperiment.stamp(base.get(i % base.size()), run, i,
                            i % liveEvery == 0 ? Priority.LIVE : Priority.VOD, BurstExperiment.BREAK_TS + (i % 2000)), live, vod);
                    ObjectNode out = Results.line("exp5_shedding");
                    out.put("label", label);
                    out.put("load_avg_before_run", load);
                    out.put("measured_capacity_per_s", capacity);
                    out.put("multiple_of_capacity", m);
                    out.put("offered_per_s", rate);
                    out.put("seconds", seconds);
                    out.put("repeat", rep);
                    out.put("live_share", liveShare);
                    out.put("traffic", "real ad-break contexts from iPinYou 2013-06-11, fresh simulated viewers per run");
                    out.put("load_generator", "same machine, separate JVM, open loop, 8 gRPC channels");
                    LoadGen.summarize(out, "live", live, seconds);
                    LoadGen.summarize(out, "vod", vod, seconds);
                    Results.append("exp5_shedding.jsonl", out);
                    System.out.printf("%s x%.1f rep=%d LIVE ok=%d shed=%d p99=%.2f | VOD ok=%d shed=%d err=%d p99=%.2f%n", label, m, rep,
                            live.ok.sum(), live.shed.sum(), out.get("live").get("p99_ms").asDouble(), vod.ok.sum(), vod.shed.sum(),
                            vod.deadline.sum() + vod.unavailable.sum() + vod.other.sum(), out.get("vod").get("p99_ms").asDouble());
                    Thread.sleep(5000);
                }
            }
        }
    }
}
