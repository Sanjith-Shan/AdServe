package adserve.core.auction;

/** What a winning impression is charged. */
public enum PricingRule {
    /**
     * Generalized second price, per pod slot: the least the winner could have bid and still kept
     * the slot against the best excluded candidate that could have taken it, floored at the
     * reserve and capped at the winner's own bid.
     */
    SECOND_PRICE,
    /** Pay your bid. The baseline, and what AdServe charged before the auction existed. */
    FIRST_PRICE;

    public String wire() {
        return this == SECOND_PRICE ? "second_price" : "first_price";
    }
}
