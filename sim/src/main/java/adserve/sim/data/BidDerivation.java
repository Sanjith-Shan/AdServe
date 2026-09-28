package adserve.sim.data;

import adserve.core.model.Pricing;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derives each campaign's bid from the prices it actually won at on the replay day.
 *
 * <p>Rule: {@code cpcBidMicros = round(median paying price per impression / smoothed click rate)},
 * so a 30-second spot at the campaign's own click rate bids exactly the median price the campaign
 * really paid. A campaign with fewer than {@link #MIN_OBSERVED} observed prices gets a synthetic
 * median instead, drawn deterministically (seeded by the campaign id) from a log-normal fitted to
 * every impression's paying price on the day.
 *
 * <p>Feed it impression rows with {@link #add(IpinyouRow)}; it also keeps the day's slot floor
 * prices and the log's own bid prices, which are what the reserve and the M0 table are built from.
 */
public final class BidDerivation {

    /** Fewer observed prices than this and the campaign's bid comes from the fitted log-normal. */
    public static final int MIN_OBSERVED = 30;

    /** A log-normal over micros per impression: {@code ln(price) ~ N(mu, sigma^2)}. */
    public record LogNormal(double mu, double sigma) {
        public double median() {
            return Math.exp(mu);
        }

        public double mean() {
            return Math.exp(mu + sigma * sigma / 2);
        }

        /** A deterministic draw keyed by {@code seed} (Box-Muller over two hashes of it). */
        public double draw(String seed) {
            double u1 = (Mapping.hash("lognormal-a:" + seed) >>> 11) * 0x1.0p-53;
            double u2 = (Mapping.hash("lognormal-b:" + seed) >>> 11) * 0x1.0p-53;
            u1 = Math.max(u1, 0x1.0p-53);
            double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
            return Math.exp(mu + sigma * z);
        }
    }

    /**
     * One campaign's derived bid.
     *
     * @param priceBasisMicros the per-impression price the bid is built from: the observed median,
     *                         or the synthetic draw when {@code fallback}
     */
    public record Bid(String campaignId, long impressions, long medianPriceMicros, long p10PriceMicros,
                      long p90PriceMicros, long medianLogBidMicros, double clickRate, long priceBasisMicros,
                      long cpcBidMicros, boolean fallback) {
        /** What one impression of this campaign bids for a spot of the given length. */
        public long valueMicros(int durationS) {
            return Pricing.impressionValueMicros(cpcBidMicros, clickRate, durationS);
        }
    }

    /** Counts of small non-negative integers (prices in fen); exact quantiles without storing rows. */
    public static final class Histogram {
        private long[] counts = new long[512];
        private long n;

        public void add(int v) {
            if (v < 0) v = 0;
            if (v >= counts.length) counts = Arrays.copyOf(counts, Math.max(v + 1, counts.length * 2));
            counts[v]++;
            n++;
        }

        public long count() {
            return n;
        }

        /** The value of rank {@code k} (0-based) in sorted order. */
        long rank(long k) {
            long seen = 0;
            for (int v = 0; v < counts.length; v++) {
                seen += counts[v];
                if (seen > k) return v;
            }
            throw new IllegalStateException("rank " + k + " of " + n);
        }

        /** Nearest-rank quantile, {@code p} in [0, 1]. */
        public long quantile(double p) {
            if (n == 0) return 0;
            long k = (long) Math.ceil(p * n) - 1;
            return rank(Math.max(0, Math.min(n - 1, k)));
        }

        /** Median; the mean of the two middle values when the count is even. */
        public double median() {
            if (n == 0) return 0;
            return (rank((n - 1) / 2) + rank(n / 2)) / 2.0;
        }

        /** Share of values strictly below {@code v}. */
        public double shareBelow(int v) {
            long below = 0;
            for (int i = 0; i < Math.min(v, counts.length); i++) below += counts[i];
            return n == 0 ? 0 : (double) below / n;
        }

        /** Share of values equal to {@code v}. */
        public double shareAt(int v) {
            return n == 0 || v >= counts.length ? 0 : (double) counts[v] / n;
        }

        /** Maximum-likelihood log-normal over the positive values, each scaled by {@code scale}. */
        public LogNormal fitLogNormal(double scale) {
            long m = 0;
            double sum = 0;
            for (int v = 1; v < counts.length; v++) {
                if (counts[v] == 0) continue;
                m += counts[v];
                sum += counts[v] * Math.log(v * scale);
            }
            if (m < 2) throw new IllegalStateException("too few positive values to fit a log-normal");
            double mu = sum / m;
            double ss = 0;
            for (int v = 1; v < counts.length; v++) {
                if (counts[v] == 0) continue;
                double d = Math.log(v * scale) - mu;
                ss += counts[v] * d * d;
            }
            return new LogNormal(mu, Math.sqrt(ss / m));
        }
    }

    private final Map<String, Histogram> pay = new LinkedHashMap<>();
    private final Map<String, Histogram> logBid = new LinkedHashMap<>();
    private final Map<String, String> advertiser = new LinkedHashMap<>();
    private final Histogram allPay = new Histogram();
    private final Histogram floors = new Histogram();
    private final Histogram allLogBid = new Histogram();
    private LogNormal model;

    /** The campaign id the loader gives an (advertiser, creative) pair. */
    public static String campaignId(String advertiser, String creative) {
        return "c" + advertiser + "-" + creative.substring(0, Math.min(8, creative.length()));
    }

    /** Micros per impression of a CPM in fen. */
    public static long micros(double fen) {
        return Math.round(fen * 10);
    }

    /** The CPC bid that makes a 30-second spot at {@code clickRate} worth {@code priceMicros}. */
    public static long cpcBid(double priceMicros, double clickRate) {
        return Math.round(priceMicros / Math.max(clickRate, 1e-6));
    }

    public void add(IpinyouRow r) {
        String id = campaignId(r.advertiser(), r.creative());
        advertiser.putIfAbsent(id, r.advertiser());
        pay.computeIfAbsent(id, k -> new Histogram()).add(r.payPrice());
        logBid.computeIfAbsent(id, k -> new Histogram()).add(r.bidPrice());
        allPay.add(r.payPrice());
        allLogBid.add(r.bidPrice());
        floors.add(r.slotPrice());
        model = null;
    }

    /** Records a paying price for a campaign directly (tests and synthetic catalogues). */
    public void addPrice(String campaignId, int payPriceFen) {
        pay.computeIfAbsent(campaignId, k -> new Histogram()).add(payPriceFen);
        allPay.add(payPriceFen);
        model = null;
    }

    /** Log-normal over every impression's positive paying price, in micros per impression. */
    public LogNormal priceModel() {
        if (model == null) model = allPay.fitLogNormal(10);
        return model;
    }

    public Bid bid(String campaignId, double clickRate) {
        Histogram h = pay.getOrDefault(campaignId, new Histogram());
        Histogram b = logBid.getOrDefault(campaignId, new Histogram());
        boolean fallback = h.count() < MIN_OBSERVED;
        long median = micros(h.median());
        double basis = fallback ? priceModel().draw(campaignId) : median;
        return new Bid(campaignId, h.count(), median, micros(h.quantile(0.10)), micros(h.quantile(0.90)),
                micros(b.median()), clickRate, Math.round(basis), cpcBid(basis, clickRate), fallback);
    }

    public Map<String, String> advertisers() {
        return advertiser;
    }

    public Histogram payHistogram(String campaignId) {
        return pay.getOrDefault(campaignId, new Histogram());
    }

    public Histogram allPay() {
        return allPay;
    }

    public Histogram floors() {
        return floors;
    }

    public Histogram allLogBid() {
        return allLogBid;
    }
}
