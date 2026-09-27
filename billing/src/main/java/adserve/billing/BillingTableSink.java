package adserve.billing;

import org.apache.flink.api.connector.sink2.Sink;
import org.apache.flink.api.connector.sink2.SinkWriter;
import org.apache.flink.api.connector.sink2.WriterInitContext;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The Publish stage's table writer: batched inserts into billing_events that do nothing on a
 * repeated event id. With Flink's at-least-once checkpoints this gives an exactly-once table:
 * a batch replayed after a failure lands on rows that already exist.
 */
public final class BillingTableSink implements Sink<BillingRow> {
    private final String url;
    private final String user;
    private final String password;
    private final String pipeline;

    public BillingTableSink(String url, String user, String password, String pipeline) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.pipeline = pipeline;
    }

    @Override
    public SinkWriter<BillingRow> createWriter(WriterInitContext context) throws IOException {
        return new Writer();
    }

    private final class Writer implements SinkWriter<BillingRow> {
        private final List<BillingRow> buffer = new ArrayList<>();
        private Connection conn;

        private Connection conn() throws SQLException {
            if (conn == null || conn.isClosed()) conn = DriverManager.getConnection(url, user, password);
            return conn;
        }

        @Override
        public void write(BillingRow r, Context context) throws IOException {
            buffer.add(r);
            if (buffer.size() >= 500) flush(false);
        }

        @Override
        public void flush(boolean endOfInput) throws IOException {
            if (buffer.isEmpty()) return;
            try (PreparedStatement ps = conn().prepareStatement("""
                    insert into billing_events (event_id, impression_id, campaign_id, creative_id, viewer_id, price_micros,
                        decided_ts_ms, client_ts_ms, serving_region, arrival_region, rerouted, pipeline)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (event_id) do nothing""")) {
                for (BillingRow r : buffer) {
                    ps.setString(1, r.eventId);
                    ps.setString(2, r.impressionId);
                    ps.setString(3, r.campaignId);
                    ps.setString(4, r.creativeId);
                    ps.setString(5, r.viewerId);
                    ps.setLong(6, r.priceMicros);
                    ps.setLong(7, r.decidedTsMs);
                    ps.setLong(8, r.clientTsMs);
                    ps.setString(9, r.servingRegion);
                    ps.setString(10, r.arrivalRegion);
                    ps.setBoolean(11, r.rerouted);
                    ps.setString(12, pipeline);
                    ps.addBatch();
                }
                ps.executeBatch();
                buffer.clear();
            } catch (SQLException e) {
                throw new IOException("billing write failed", e);
            }
        }

        @Override
        public void close() throws Exception {
            flush(true);
            if (conn != null) conn.close();
        }
    }
}
