package adserve.core.pacing;

/**
 * What a pacer sees at the start of {@code slot}: its budget, what has been spent so far, and what
 * happened during the slot that just ended.
 *
 * @param lastSlotSpendMicros   spend attributed to this campaign during the previous slot
 * @param lastSlotEligible      requests during the previous slot where the campaign passed every
 *                              stage before the pacing gate
 */
public record PacingState(int slot, long budgetMicros, long spentMicros, long lastSlotSpendMicros,
                         long lastSlotEligible) {
    public long remainingMicros() {
        return Math.max(0, budgetMicros - spentMicros);
    }
}
