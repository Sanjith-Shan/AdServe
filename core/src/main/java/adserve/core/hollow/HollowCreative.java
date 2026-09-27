package adserve.core.hollow;

import com.netflix.hollow.core.write.objectmapper.HollowInline;

public class HollowCreative {
    @HollowInline
    public String id;
    public int durationS;
    public double clickRate;
}
