package adserve.server.legacy;

import ads.v1.DecisionRecord;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * The design AdServe exists to avoid, kept as the experiment baseline: in --legacy-sync-write
 * mode each decision is inserted into Postgres before the response is sent, so every ad break
 * costs a must-succeed database write on the critical path and a database outage is a serving
 * outage.
 */
public final class SyncDecisionWriter {
    private final JdbcTemplate jdbc;

    public SyncDecisionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(DecisionRecord r) {
        jdbc.update("insert into decisions_sync (request_id, viewer_id, decided_at, pod_size, pod_value_micros, record) "
                        + "values (?, ?, ?, ?, ?, ?) on conflict (request_id) do nothing",
                r.getResponse().getRequestId(), r.getResponse().getViewerId(),
                Timestamp.from(Instant.ofEpochMilli(r.getResponse().getDecidedTsMs())),
                r.getResponse().getPodCount(), r.getPodValueMicros(), r.toByteArray());
    }
}
