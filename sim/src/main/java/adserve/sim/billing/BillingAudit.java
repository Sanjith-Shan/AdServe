package adserve.sim.billing;

import adserve.beacons.RedisCounters;
import adserve.core.caps.Windows;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Experiment 7: the billing pipeline end to end, with an automated audit. Simulated players
 * request breaks from a running AdServe and fire beacons with injected duplicates, losses and
 * misroutes; the Flink job and the beacon consumer both count them. The audit then checks
 * <ul>
 *   <li>the billing table against the ground truth (every impression whose IMPRESSION beacon was
 *       sent at least once, counted exactly once), and</li>
 *   <li>the billing table against an independent implementation, the beacon consumer's confirmed
 *       spend counters in Redis, campaign by campaign: two pipelines, one answer.</li>
 * </ul>
 * Args: breaks dupRate [grpc] [kafka] [jdbc] [redis]
 */
public final class BillingAudit {
    public static void main(String[] a) throws Exception {
        int breaks = a.length > 0 ? Integer.parseInt(a[0]) : 5000;
        double dup = a.length > 1 ? Double.parseDouble(a[1]) : 0.1;
        String grpc = a.length > 2 ? a[2] : "localhost:29100";
        String kafka = a.length > 3 ? a[3] : "localhost:29092";
        String jdbc = a.length > 4 ? a[4] : "jdbc:postgresql://localhost:25432/adserve";
        String redis = a.length > 5 ? a[5] : "redis://localhost:26379";
        long settleMs = Long.parseLong(System.getenv().getOrDefault("SETTLE_MS", "30000"));
        String run = "p" + UUID.randomUUID().toString().substring(0, 6);

        long t0 = System.currentTimeMillis();
        PlayerSim.Truth truth = PlayerSim.play(grpc, kafka, Path.of("data/work/requests-20130611.bin"), breaks, run,
                dup, 0.01, 0.02, 99);
        System.out.printf("played %d breaks: %d impressions, %d beacons (%d duplicates), waiting %d ms%n",
                breaks, truth.servedImpressions().size(), truth.beaconsSent(), truth.duplicatesSent(), settleMs);

        // Wait until the billing table stops growing for this run (or the settle time passes).
        long billed = -1, lastChange = System.currentTimeMillis();
        Map<String, Long> billedByCampaign = new HashMap<>();
        Set<String> billedImpressions = new HashSet<>();
        long duplicateRows = 0, rerouted = 0, day = -1;
        while (System.currentTimeMillis() - lastChange < settleMs) {
            Thread.sleep(2000);
            billedByCampaign.clear();
            billedImpressions.clear();
            long rows = 0;
            duplicateRows = 0;
            rerouted = 0;
            try (Connection c = DriverManager.getConnection(jdbc, "adserve", "adserve");
                 PreparedStatement ps = c.prepareStatement(
                         "select impression_id, campaign_id, price_micros, rerouted, decided_ts_ms from billing_events where viewer_id like ?")) {
                ps.setString(1, run + "-%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows++;
                        if (!billedImpressions.add(rs.getString(1))) duplicateRows++;
                        billedByCampaign.merge(rs.getString(2), rs.getLong(3), Long::sum);
                        if (rs.getBoolean(4)) rerouted++;
                        day = Windows.day(rs.getLong(5));
                    }
                }
            }
            if (rows != billed) {
                billed = rows;
                lastChange = System.currentTimeMillis();
            }
        }

        Set<String> missing = new HashSet<>(truth.beaconedImpressions());
        missing.removeAll(billedImpressions);
        Set<String> phantom = new HashSet<>(billedImpressions);
        phantom.removeAll(truth.beaconedImpressions());

        // Parallel-run audit: the beacon consumer's idempotent spend counters, per campaign. Its
        // counters cover every run on this day, so compare against the whole day's billing table.
        long agree = 0, disagree = 0;
        Map<String, Long> tableByCampaign = new HashMap<>();
        try (Connection c = DriverManager.getConnection(jdbc, "adserve", "adserve");
             PreparedStatement ps = c.prepareStatement(
                     "select campaign_id, sum(price_micros) from billing_events group by campaign_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tableByCampaign.put(rs.getString(1), rs.getLong(2));
            }
        }
        List<String> ids = new ArrayList<>(tableByCampaign.keySet());
        try (RedisCounters rc = new RedisCounters(redis, 2000, true)) {
            long[] spend = rc.spend(ids, day);
            for (int i = 0; i < ids.size(); i++) {
                if (spend[i] == tableByCampaign.get(ids.get(i))) agree++;
                else disagree++;
            }
        }

        ObjectNode out = Results.line("exp7_billing");
        out.put("traffic", "first " + breaks + " ad breaks of iPinYou 2013-06-11, simulated players");
        out.put("duplicate_probability", dup);
        out.put("lost_probability", 0.01);
        out.put("misroute_probability", 0.02);
        out.put("impressions_served", truth.servedImpressions().size());
        out.put("impressions_with_impression_beacon", truth.beaconedImpressions().size());
        out.put("beacons_sent", truth.beaconsSent());
        out.put("duplicate_beacons_sent", truth.duplicatesSent());
        out.put("misrouted_beacons", truth.misrouted());
        out.put("billing_rows", billed);
        out.put("billing_duplicate_rows", duplicateRows);
        out.put("billing_rerouted_rows", rerouted);
        out.put("missing_from_billing", missing.size());
        out.put("billed_without_beacon", phantom.size());
        out.put("audit_campaigns_agree", agree);
        out.put("audit_campaigns_disagree", disagree);
        if (agree + disagree == 0) out.putNull("audit_agreement");
        else out.put("audit_agreement", (double) agree / (agree + disagree));
        out.put("wall_seconds", (System.currentTimeMillis() - t0) / 1000.0);
        Results.append("exp7_billing.jsonl", out);
        System.out.println(out.toPrettyString());
        if (billed <= 0) {
            System.err.println("the billing table is empty for this run: is the billing job running?");
            System.exit(1);
        }
    }
}
