package adserve.server.campaigns;

import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The campaign store in Postgres. Only the snapshot refresher and the management API call it. */
@Repository
public class CampaignRepository {
    private final JdbcTemplate jdbc;

    public CampaignRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Campaign> loadAll() {
        Map<String, List<CreativeSpec>> creatives = new HashMap<>();
        jdbc.query("select id, campaign_id, duration_s, click_rate from creatives order by id", rs -> {
            creatives.computeIfAbsent(rs.getString(2), k -> new ArrayList<>())
                    .add(new CreativeSpec(rs.getString(1), rs.getInt(3), rs.getDouble(4)));
        });
        Map<String, Campaign> out = new LinkedHashMap<>();
        jdbc.query("""
                select c.id, c.advertiser_id, c.name, c.category, c.cpc_bid_micros, f.daily_budget_micros,
                       f.starts_at, f.ends_at, c.pacer, c.cap_per_day, c.cap_per_week, c.targeting::text, c.active
                from campaigns c join flights f on f.campaign_id = c.id
                order by c.id""", rs -> {
            String id = rs.getString(1);
            out.put(id, new Campaign(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                    rs.getLong(6), rs.getTimestamp(7).getTime(), rs.getTimestamp(8).getTime(),
                    PacerKind.valueOf(rs.getString(9)), new FrequencyCap(rs.getInt(10), rs.getInt(11)),
                    parseTargeting(rs.getString(12)), creatives.getOrDefault(id, List.of()), rs.getBoolean(13)));
        });
        return new ArrayList<>(out.values());
    }

    static TargetingSpec parseTargeting(String json) {
        try {
            if (json == null || json.isBlank() || json.equals("{}")) return TargetingSpec.ANY;
            return CampaignFiles.mapper().readValue(json, TargetingSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad targeting json: " + json, e);
        }
    }

    @Transactional
    public void upsert(Campaign c, String advertiserName) {
        String targeting;
        try {
            targeting = CampaignFiles.mapper().writeValueAsString(c.targeting());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        jdbc.update("insert into advertisers (id, name) values (?, ?) on conflict (id) do nothing",
                c.advertiserId(), advertiserName);
        jdbc.update("""
                insert into campaigns (id, advertiser_id, name, category, cpc_bid_micros, pacer, cap_per_day,
                                       cap_per_week, targeting, active)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                on conflict (id) do update set name = excluded.name, category = excluded.category,
                    cpc_bid_micros = excluded.cpc_bid_micros, pacer = excluded.pacer,
                    cap_per_day = excluded.cap_per_day, cap_per_week = excluded.cap_per_week,
                    targeting = excluded.targeting, active = excluded.active, updated_at = now()""",
                c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), c.pacer().name(),
                c.cap().perDay(), c.cap().perWeek(), targeting, c.active());
        jdbc.update("""
                insert into flights (campaign_id, starts_at, ends_at, daily_budget_micros) values (?, ?, ?, ?)
                on conflict (campaign_id) do update set starts_at = excluded.starts_at, ends_at = excluded.ends_at,
                    daily_budget_micros = excluded.daily_budget_micros""",
                c.id(), ts(c.flightStartMs()), ts(c.flightEndMs()), c.dailyBudgetMicros());
        for (CreativeSpec cr : c.creatives()) {
            addCreative(c.id(), cr);
        }
    }

    public void addCreative(String campaignId, CreativeSpec cr) {
        jdbc.update("""
                insert into creatives (id, campaign_id, duration_s, click_rate) values (?, ?, ?, ?)
                on conflict (id) do update set duration_s = excluded.duration_s, click_rate = excluded.click_rate""",
                cr.id(), campaignId, cr.durationS(), cr.clickRate());
    }

    public java.util.Optional<Campaign> find(String id) {
        return loadAll().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    public Map<String, String> advertisers() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("select id, name from advertisers order by id", rs -> {
            out.put(rs.getString(1), rs.getString(2));
        });
        return out;
    }

    public boolean setActive(String id, boolean active) {
        return jdbc.update("update campaigns set active = ?, updated_at = now() where id = ?", active, id) == 1;
    }

    public int count() {
        Integer n = jdbc.queryForObject("select count(*) from campaigns", Integer.class);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(long ms) {
        return Timestamp.from(Instant.ofEpochMilli(ms));
    }
}
