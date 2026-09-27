package adserve.sim.billing;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * The demo's traffic: simulated players request breaks and fire beacons (10% duplicated), then
 * the demo prints what the billing table counted against what was served.
 */
public final class Demo {
    public static void main(String[] a) throws Exception {
        int breaks = a.length > 0 ? Integer.parseInt(a[0]) : 2000;
        String run = "demo" + UUID.randomUUID().toString().substring(0, 4);
        System.out.printf("playing %d real ad breaks as simulated viewers (run %s)%n", breaks, run);
        PlayerSim.Truth t = PlayerSim.play("localhost:29100", "localhost:29092",
                Path.of(a.length > 1 ? a[1] : "data/sample/requests-20130611-first20k.bin"), breaks, run, 0.10, 0.01, 0.02, 7);
        System.out.printf("served %d impressions; devices sent %d beacons, %d of them duplicates, %d to the wrong region%n",
                t.servedImpressions().size(), t.beaconsSent(), t.duplicatesSent(), t.misrouted());
        long rows = 0;
        for (int i = 0; i < 30; i++) {
            Thread.sleep(2000);
            try (Connection c = DriverManager.getConnection("jdbc:postgresql://localhost:25432/adserve", "adserve", "adserve");
                 ResultSet rs = c.createStatement().executeQuery(
                         "select count(*), count(distinct impression_id) from billing_events where viewer_id like '" + run + "-%'")) {
                rs.next();
                rows = rs.getLong(1);
                if (rows >= t.beaconedImpressions().size()) break;
            }
        }
        System.out.printf("billing table: %d rows for %d impressions whose IMPRESSION beacon was sent (duplicates billed: %d)%n",
                rows, t.beaconedImpressions().size(), Math.max(0, rows - t.beaconedImpressions().size()));
    }
}
