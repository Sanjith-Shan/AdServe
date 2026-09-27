package adserve.core.engine;

import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Pricing;
import adserve.core.targeting.TargetingIndex;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable, fully compiled view of every campaign, swapped in atomically when the store
 * changes. Everything the decision path needs per campaign is precomputed here: compiled
 * targeting, dense advertiser and category ids for pod assembly, and each creative's value.
 */
public final class CampaignSnapshot {
    private final long version;
    private final List<Campaign> campaigns;
    private final TargetingIndex targeting;
    private final Map<String, Integer> indexById = new HashMap<>();
    private final int[] advertiserId;
    private final int[] categoryId;
    private final long[][] creativeValue;

    public CampaignSnapshot(long version, List<Campaign> campaigns) {
        this.version = version;
        this.campaigns = List.copyOf(campaigns);
        this.targeting = new TargetingIndex(this.campaigns);
        int n = this.campaigns.size();
        advertiserId = new int[n];
        categoryId = new int[n];
        creativeValue = new long[n][];
        Map<String, Integer> adv = new HashMap<>();
        Map<String, Integer> cat = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Campaign c = this.campaigns.get(i);
            if (indexById.put(c.id(), i) != null) throw new IllegalArgumentException("duplicate campaign " + c.id());
            advertiserId[i] = adv.computeIfAbsent(c.advertiserId(), k -> adv.size());
            categoryId[i] = cat.computeIfAbsent(c.category(), k -> cat.size());
            List<CreativeSpec> cr = c.creatives();
            creativeValue[i] = new long[cr.size()];
            for (int j = 0; j < cr.size(); j++) {
                CreativeSpec s = cr.get(j);
                creativeValue[i][j] = Pricing.impressionValueMicros(c.cpcBidMicros(), s.clickRate(), s.durationS());
            }
        }
    }

    public static CampaignSnapshot empty() {
        return new CampaignSnapshot(0, List.of());
    }

    public long version() {
        return version;
    }

    public int size() {
        return campaigns.size();
    }

    public Campaign campaign(int i) {
        return campaigns.get(i);
    }

    public List<Campaign> campaigns() {
        return campaigns;
    }

    public TargetingIndex targeting() {
        return targeting;
    }

    public int indexOf(String campaignId) {
        Integer i = indexById.get(campaignId);
        return i == null ? -1 : i;
    }

    public int advertiser(int i) {
        return advertiserId[i];
    }

    public int category(int i) {
        return categoryId[i];
    }

    public long creativeValue(int campaign, int creative) {
        return creativeValue[campaign][creative];
    }
}
