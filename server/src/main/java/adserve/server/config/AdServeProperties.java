package adserve.server.config;

import ads.v1.Region;
import adserve.core.caps.CapMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the server reads from {@code adserve.*}.
 *
 * @param executor        "virtual" (a virtual thread per request) or "platform:N" (a fixed pool)
 * @param legacySyncWrite the experiment baseline: insert every decision into Postgres before
 *                        responding (the design the hot path avoids)
 */
@ConfigurationProperties("adserve")
public record AdServeProperties(
        int grpcPort,
        Region servingRegion,
        Kafka kafka,
        Redis redis,
        CapMode capMode,
        String solver,
        long snapshotRefreshMs,
        boolean legacySyncWrite,
        boolean useRequestClock,
        long pacingSlotMs,
        String forecastFile,
        String seedFile,
        String executor,
        Hollow hollow,
        Shedding shedding,
        Auction auction) {

    /**
     * The auction. {@code pricing} is "second_price" or "first_price"; {@code reserveMicros} is the
     * per-slot reserve; {@code qualityWeight} above 0 turns on the skip-rate penalty, read from the
     * beacon consumer's per-creative counters once a creative has {@code qualityMinImpressions}.
     */
    public record Auction(String pricing, long reserveMicros, double qualityWeight, long qualityMinImpressions) {
        public adserve.core.auction.AuctionConfig config() {
            adserve.core.auction.PricingRule rule = "first_price".equalsIgnoreCase(pricing)
                    ? adserve.core.auction.PricingRule.FIRST_PRICE : adserve.core.auction.PricingRule.SECOND_PRICE;
            return new adserve.core.auction.AuctionConfig(rule, reserveMicros, qualityWeight);
        }
    }

    /**
     * Campaign snapshot delivery. {@code source} is "postgres" (each node polls the store) or
     * "hollow" (each node consumes Hollow snapshots and deltas from {@code dir}); with
     * {@code publish}, this node also runs the publisher that reads the store and produces them.
     */
    public record Hollow(String source, String dir, boolean publish) {}

    public record Kafka(String bootstrap, String topic, int bufferRecords) {}

    public record Redis(String uri, long capTimeoutMs, int connections) {}

    /**
     * Priority load shedding. {@code capacityPerSecond} is the measured sustainable decision rate;
     * VOD may not draw the last {@code liveReserveFraction} of the token bucket, which is kept for
     * LIVE. {@code maxInFlight} bounds concurrent decisions regardless of class.
     */
    public record Shedding(boolean enabled, double capacityPerSecond, double burstSeconds,
                           double liveReserveFraction, int maxInFlight, long retryAfterMs) {}
}
