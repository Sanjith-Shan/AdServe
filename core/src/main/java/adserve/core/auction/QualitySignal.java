package adserve.core.auction;

/**
 * Where the optional quality term reads a creative's skip rate (impressions without a COMPLETE
 * beacon, over impressions), in [0, 1]. The default knows nothing and returns 0.
 */
@FunctionalInterface
public interface QualitySignal {
    QualitySignal NONE = creativeId -> 0.0;

    double skipRate(String creativeId);
}
