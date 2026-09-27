package adserve.billing;

/** A normalized, verified beacon: its stable event id and where it arrived. A Flink POJO. */
public class BeaconEvent {
    public String eventId;
    public String impressionId;
    public int type;
    public int offsetS;
    public long clientTsMs;
    public String arrivalRegion;
    public String servingRegion;

    public BeaconEvent() {}

    public BeaconEvent(String eventId, String impressionId, int type, int offsetS, long clientTsMs,
                       String arrivalRegion, String servingRegion) {
        this.eventId = eventId;
        this.impressionId = impressionId;
        this.type = type;
        this.offsetS = offsetS;
        this.clientTsMs = clientTsMs;
        this.arrivalRegion = arrivalRegion;
        this.servingRegion = servingRegion;
    }
}
