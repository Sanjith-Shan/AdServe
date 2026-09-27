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
        Shedding shedding) {

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
