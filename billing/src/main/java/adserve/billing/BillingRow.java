package adserve.billing;

/** One billable impression: the joined, deduplicated output of the job. A Flink POJO. */
public class BillingRow {
    public String eventId;
    public String impressionId;
    public String campaignId;
    public String creativeId;
    public String viewerId;
    public long priceMicros;
    public long decidedTsMs;
    public long clientTsMs;
    public String servingRegion;
    public String arrivalRegion;
    public boolean rerouted;

    public BillingRow() {}

    static BillingRow of(ImpressionContext c, BeaconEvent b) {
        BillingRow r = new BillingRow();
        r.eventId = b.eventId;
        r.impressionId = c.impressionId;
        r.campaignId = c.campaignId;
        r.creativeId = c.creativeId;
        r.viewerId = c.viewerId;
        r.priceMicros = c.priceMicros;
        r.decidedTsMs = c.decidedTsMs;
        r.clientTsMs = b.clientTsMs;
        r.servingRegion = c.servingRegion;
        r.arrivalRegion = b.arrivalRegion;
        r.rerouted = b.arrivalRegion != null && !b.arrivalRegion.equals(c.servingRegion);
        return r;
    }
}
