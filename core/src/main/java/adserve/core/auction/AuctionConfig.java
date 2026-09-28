package adserve.core.auction;

/**
 * The auction's knobs.
 *
 * @param pricing        second price (default) or first price
 * @param reserveMicros  the least one impression in any slot clears at, in micros; a creative whose
 *                       own bid value is below it does not enter the auction
 * @param qualityWeight  weight of the skip-rate penalty in the auction score; 0 (the default)
 *                       switches the quality term off, and no published figure uses it
 */
public record AuctionConfig(PricingRule pricing, long reserveMicros, double qualityWeight) {
    public AuctionConfig {
        if (pricing == null) throw new IllegalArgumentException("pricing rule required");
        if (reserveMicros < 0) throw new IllegalArgumentException("negative reserve");
        if (qualityWeight < 0 || qualityWeight > 1) throw new IllegalArgumentException("quality weight must be in [0, 1]");
    }

    public static AuctionConfig defaults() {
        return new AuctionConfig(PricingRule.SECOND_PRICE, 0, 0);
    }

    public AuctionConfig withPricing(PricingRule p) {
        return new AuctionConfig(p, reserveMicros, qualityWeight);
    }

    public AuctionConfig withReserve(long micros) {
        return new AuctionConfig(pricing, micros, qualityWeight);
    }

    public AuctionConfig withQualityWeight(double w) {
        return new AuctionConfig(pricing, reserveMicros, w);
    }
}
