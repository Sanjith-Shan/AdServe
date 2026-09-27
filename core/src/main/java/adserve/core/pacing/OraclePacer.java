package adserve.core.pacing;

import adserve.core.model.PacerKind;

/**
 * Baseline that knows, in advance, how much the campaign would spend in every slot at rate 1
 * (measured by an unthrottled, unbudgeted pass over the same day). Its rate for each slot is the
 * remaining budget over the remaining full-rate spend, which spends evenly against real traffic.
 * It exists only in the simulator; no serving system has this information.
 */
public final class OraclePacer implements Pacer {
    private final long[] fullRateSpend;
    private final long[] suffix;
    private volatile double rate = 1.0;

    public OraclePacer(long[] fullRateSpendPerSlot) {
        this.fullRateSpend = fullRateSpendPerSlot.clone();
        this.suffix = new long[fullRateSpend.length + 1];
        for (int i = fullRateSpend.length - 1; i >= 0; i--) suffix[i] = suffix[i + 1] + fullRateSpend[i];
    }

    @Override
    public PacerKind kind() {
        return PacerKind.ORACLE;
    }

    @Override
    public double rate() {
        return rate;
    }

    @Override
    public void update(PacingState s) {
        int t = Math.max(0, Math.min(fullRateSpend.length, s.slot()));
        long remainingFull = suffix[t];
        rate = remainingFull <= 0 ? 1.0 : Math.min(1.0, (double) s.remainingMicros() / remainingFull);
    }
}
