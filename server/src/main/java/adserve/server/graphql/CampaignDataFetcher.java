package adserve.server.graphql;

import adserve.beacons.RedisCounters;
import adserve.core.caps.Windows;
import adserve.core.engine.BudgetLedger;
import adserve.core.engine.PacingController;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Device;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.Pricing;
import adserve.core.model.TargetingSpec;
import adserve.server.budget.BudgetSync;
import adserve.server.campaigns.CampaignCache;
import adserve.server.campaigns.CampaignRepository;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.netflix.graphql.dgs.exceptions.DgsEntityNotFoundException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The campaign management API on DGS. Reads come from the store (not the serving snapshot) so a
 * manager sees what was written; every write refreshes the serving snapshot immediately, so the
 * next decision can serve a campaign created here.
 */
@DgsComponent
public class CampaignDataFetcher {
    private final CampaignRepository repo;
    private final CampaignCache cache;
    private final BudgetLedger ledger;
    private final PacingController pacing;
    private final RedisCounters counters;
    private final BudgetSync budgetSync;

    public CampaignDataFetcher(CampaignRepository repo, CampaignCache cache, BudgetLedger ledger,
                               PacingController pacing, RedisCounters counters, BudgetSync budgetSync) {
        this.repo = repo;
        this.cache = cache;
        this.ledger = ledger;
        this.pacing = pacing;
        this.counters = counters;
        this.budgetSync = budgetSync;
    }

    public record AdvertiserView(String id, String name) {}

    @DgsQuery
    public List<AdvertiserView> advertisers() {
        return repo.advertisers().entrySet().stream().map(e -> new AdvertiserView(e.getKey(), e.getValue())).toList();
    }

    @DgsQuery
    public List<Campaign> campaigns(@InputArgument String advertiserId, @InputArgument Boolean activeOnly) {
        return repo.loadAll().stream()
                .filter(c -> advertiserId == null || c.advertiserId().equals(advertiserId))
                .filter(c -> activeOnly == null || !activeOnly || c.active())
                .toList();
    }

    @DgsQuery
    public Campaign campaign(@InputArgument String id) {
        return repo.find(id).orElse(null);
    }

    @DgsData(parentType = "Advertiser", field = "campaigns")
    public List<Campaign> advertiserCampaigns(DgsDataFetchingEnvironment env) {
        AdvertiserView a = env.getSource();
        return campaigns(a.id(), false);
    }

    @DgsData(parentType = "Campaign", field = "advertiser")
    public AdvertiserView advertiser(DgsDataFetchingEnvironment env) {
        Campaign c = env.getSource();
        return new AdvertiserView(c.advertiserId(), repo.advertisers().getOrDefault(c.advertiserId(), c.advertiserId()));
    }

    @DgsData(parentType = "Campaign", field = "flight")
    public Map<String, Object> flight(DgsDataFetchingEnvironment env) {
        Campaign c = env.getSource();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startsAt", Instant.ofEpochMilli(c.flightStartMs()).toString());
        m.put("endsAt", Instant.ofEpochMilli(c.flightEndMs()).toString());
        m.put("dailyBudgetMicros", c.dailyBudgetMicros());
        return m;
    }

    @DgsData(parentType = "Campaign", field = "creatives")
    public List<Map<String, Object>> creatives(DgsDataFetchingEnvironment env) {
        Campaign c = env.getSource();
        List<Map<String, Object>> out = new ArrayList<>();
        for (CreativeSpec cr : c.creatives()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", cr.id());
            m.put("durationS", cr.durationS());
            m.put("clickRate", cr.clickRate());
            m.put("valueMicros", Pricing.impressionValueMicros(c.cpcBidMicros(), cr.clickRate(), cr.durationS()));
            out.add(m);
        }
        return out;
    }

    @DgsData(parentType = "Campaign", field = "delivery")
    public Map<String, Object> delivery(DgsDataFetchingEnvironment env) {
        Campaign c = env.getSource();
        long day = Windows.day(budgetSync.lastDecisionMs());
        long spend = ledger.spent(c.id(), day);
        long confirmed;
        try {
            confirmed = counters.spend(List.of(c.id()), day)[0];
        } catch (RuntimeException e) {
            confirmed = -1;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", LocalDate.ofEpochDay(day).toString());
        m.put("spendMicros", spend);
        m.put("confirmedSpendMicros", confirmed);
        m.put("budgetFraction", c.dailyBudgetMicros() == 0 ? 0.0 : (double) spend / c.dailyBudgetMicros());
        double rate = pacing.rate(c.id());
        m.put("pacingRate", Double.isNaN(rate) ? null : rate);
        return m;
    }

    @DgsMutation
    @SuppressWarnings("unchecked")
    public Campaign createCampaign(@InputArgument("input") Map<String, Object> in) {
        Map<String, Object> t = (Map<String, Object>) in.getOrDefault("targeting", Map.of());
        if (t == null) t = Map.of();
        TargetingSpec targeting = new TargetingSpec(
                Set.copyOf(strings(t.get("geos"))),
                Set.copyOf(strings(t.get("devices")).stream().map(Device::valueOf).toList()),
                Set.copyOf(strings(t.get("segmentsAny"))),
                Set.copyOf(strings(t.get("excludedGenres"))));
        List<CreativeSpec> creatives = new ArrayList<>();
        for (Map<String, Object> cr : (List<Map<String, Object>>) in.get("creatives")) creatives.add(creative(cr));
        Campaign c = new Campaign(
                (String) in.get("id"), (String) in.get("advertiserId"), (String) in.get("name"), (String) in.get("category"),
                ((Number) in.get("cpcBidMicros")).longValue(), ((Number) in.get("dailyBudgetMicros")).longValue(),
                parseTime((String) in.get("startsAt")), parseTime((String) in.get("endsAt")),
                PacerKind.valueOf(String.valueOf(in.getOrDefault("pacer", "THROTTLE"))),
                new FrequencyCap(intOr(in.get("capPerDay")), intOr(in.get("capPerWeek"))),
                targeting, creatives, true);
        String advName = in.get("advertiserName") == null ? c.advertiserId() : (String) in.get("advertiserName");
        repo.upsert(c, advName);
        cache.refreshNow();
        return repo.find(c.id()).orElseThrow();
    }

    @DgsMutation
    public Campaign addCreative(@InputArgument String campaignId, @InputArgument("input") Map<String, Object> in) {
        if (repo.find(campaignId).isEmpty()) throw new DgsEntityNotFoundException("no campaign " + campaignId);
        repo.addCreative(campaignId, creative(in));
        cache.refreshNow();
        return repo.find(campaignId).orElseThrow();
    }

    @DgsMutation
    public Campaign setCampaignActive(@InputArgument String id, @InputArgument Boolean active) {
        if (!repo.setActive(id, active)) throw new DgsEntityNotFoundException("no campaign " + id);
        cache.refreshNow();
        return repo.find(id).orElseThrow();
    }

    private static CreativeSpec creative(Map<String, Object> m) {
        return new CreativeSpec((String) m.get("id"), ((Number) m.get("durationS")).intValue(),
                ((Number) m.get("clickRate")).doubleValue());
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        if (o == null) return List.of();
        return ((List<Object>) o).stream().map(String::valueOf).toList();
    }

    private static int intOr(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /** Accepts an ISO instant or a plain date (midnight UTC). */
    static long parseTime(String s) {
        if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return Instant.parse(s).toEpochMilli();
    }
}
