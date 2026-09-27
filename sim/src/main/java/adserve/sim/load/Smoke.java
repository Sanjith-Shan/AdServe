package adserve.sim.load;

import ads.v1.AdRequest;
import adserve.core.io.RequestFiles;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

/**
 * CI's load smoke: {@code seconds} of open-loop load at {@code rate}, cycling the sample requests.
 * Fails (exit 1) on any error or if no ads were served.
 */
public final class Smoke {
    public static void main(String[] a) throws Exception {
        Path file = Path.of(a.length > 0 ? a[0] : "data/sample/requests-20130611-first20k.bin");
        double rate = a.length > 1 ? Double.parseDouble(a[1]) : 500;
        int seconds = a.length > 2 ? Integer.parseInt(a[2]) : 30;
        String target = a.length > 3 ? a[3] : "localhost:29100";
        List<AdRequest> reqs = RequestFiles.readAll(file, 20_000);
        int n = (int) (rate * seconds);
        LoadGen.Tally live = new LoadGen.Tally(), vod = new LoadGen.Tally();
        try (LoadGen gen = new LoadGen(target, 2, 5_000)) {
            gen.run(n, LoadGen.constantRate(rate), i -> reqs.get(i % reqs.size()).toBuilder()
                    .setRequestId("smoke-" + i).build(), live, vod);
        }
        ObjectNode out = Results.line("ci_smoke");
        out.put("offered_per_s", rate);
        out.put("seconds", seconds);
        LoadGen.summarize(out, "vod", vod, seconds);
        System.out.println(out.toPrettyString());
        boolean ok = vod.ok.sum() == n && vod.podAds.sum() > 0;
        System.exit(ok ? 0 : 1);
    }
}
