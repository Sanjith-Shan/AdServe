package adserve.core.hollow;

import com.netflix.hollow.core.write.objectmapper.HollowInline;
import com.netflix.hollow.core.write.objectmapper.HollowPrimaryKey;

import java.util.List;

/** The campaign as Hollow stores it. Targeting travels as its JSON form. */
@HollowPrimaryKey(fields = "id")
public class HollowCampaign {
    @HollowInline
    public String id;
    @HollowInline
    public String advertiserId;
    @HollowInline
    public String name;
    @HollowInline
    public String category;
    public long cpcBidMicros;
    public long dailyBudgetMicros;
    public long flightStartMs;
    public long flightEndMs;
    @HollowInline
    public String pacer;
    public int capPerDay;
    public int capPerWeek;
    @HollowInline
    public String targetingJson;
    public List<HollowCreative> creatives;
    public boolean active;
}
