package adserve.billing;

/** One served impression as the decision log recorded it. A Flink POJO. */
public class ImpressionContext {
    public String impressionId;
    public String campaignId;
    public String creativeId;
    public String viewerId;
    public long priceMicros;
    public long decidedTsMs;
    public String servingRegion;

    public ImpressionContext() {}

    public ImpressionContext(String impressionId, String campaignId, String creativeId, String viewerId,
                             long priceMicros, long decidedTsMs, String servingRegion) {
        this.impressionId = impressionId;
        this.campaignId = campaignId;
        this.creativeId = creativeId;
        this.viewerId = viewerId;
        this.priceMicros = priceMicros;
        this.decidedTsMs = decidedTsMs;
        this.servingRegion = servingRegion;
    }
}
