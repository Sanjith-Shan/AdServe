package adserve.sim.data;

import adserve.core.hollow.CampaignHollow;
import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Experiment 8: delivering the campaign snapshot with Hollow. For the real 55-campaign catalogue
 * and for a 5,000-campaign catalogue (the real campaigns replicated under new ids), it measures the
 * snapshot blob, the delta blob after one campaign's budget changes, and the time from the
 * producer's announcement to a watching consumer having rebuilt its compiled snapshot.
 */
public final class HollowExperiment {
    public static void main(String[] a) throws Exception {
        List<Campaign> real = CampaignFiles.read(Path.of(a.length > 0 ? a[0] : "data/work/campaigns.json"));
        for (int size : new int[]{real.size(), 5000}) run(scale(real, size));
    }

    static List<Campaign> scale(List<Campaign> real, int n) {
        List<Campaign> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Campaign c = real.get(i % real.size());
            String id = i < real.size() ? c.id() : c.id() + "-x" + i;
            out.add(new Campaign(id, c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), c.dailyBudgetMicros(),
                    c.flightStartMs(), c.flightEndMs(), c.pacer(), c.cap(), c.targeting(), c.creatives(), c.active()));
        }
        return out;
    }

    static void run(List<Campaign> campaigns) throws Exception {
        Path dir = Files.createTempDirectory("hollow-exp");
        CampaignHollow.Publisher pub = new CampaignHollow.Publisher(dir);
        long v1 = pub.publish(campaigns);
        CampaignHollow.Source src = new CampaignHollow.Source(dir, true);
        waitFor(src, v1);
        long snapshotBytes = sizeOf(dir, "snapshot");

        long[] propagationMs = new long[20];
        long deltaBytesTotal = 0;
        List<Campaign> cur = new ArrayList<>(campaigns);
        for (int k = 0; k < propagationMs.length; k++) {
            Set<Path> before = files(dir);
            Campaign c = cur.get(k);
            cur.set(k, new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(),
                    c.dailyBudgetMicros() + 1000, c.flightStartMs(), c.flightEndMs(), c.pacer(), c.cap(), c.targeting(),
                    c.creatives(), c.active()));
            long t0 = System.nanoTime();
            long v = pub.publish(cur);
            waitFor(src, v);
            propagationMs[k] = (src.lastRefreshNanos() - t0) / 1_000_000;
            for (Path p : files(dir)) {
                if (!before.contains(p) && p.getFileName().toString().startsWith("delta")) deltaBytesTotal += Files.size(p);
            }
        }
        java.util.Arrays.sort(propagationMs);
        ObjectNode out = Results.line("exp8_hollow");
        out.put("campaigns", campaigns.size());
        out.put("snapshot_blob_bytes", snapshotBytes);
        out.put("mean_delta_blob_bytes_one_campaign_changed", deltaBytesTotal / propagationMs.length);
        out.put("changes", propagationMs.length);
        out.put("deltas_applied", src.deltasApplied.get());
        out.put("publish_to_consumer_rebuilt_ms_p50", propagationMs[propagationMs.length / 2]);
        out.put("publish_to_consumer_rebuilt_ms_max", propagationMs[propagationMs.length - 1]);
        out.put("note", "filesystem blob store and announcement watcher on one machine; the watcher polls once a second");
        Results.append("exp8_hollow.jsonl", out);
        System.out.println(out.toPrettyString());
    }

    static void waitFor(CampaignHollow.Source src, long v) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (src.current().version() != v && System.currentTimeMillis() < deadline) Thread.sleep(2);
    }

    static Set<Path> files(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.collect(Collectors.toSet());
        }
    }

    static long sizeOf(Path dir, String prefix) throws Exception {
        long n = 0;
        for (Path p : files(dir)) if (p.getFileName().toString().startsWith(prefix)) n += Files.size(p);
        return n;
    }
}
