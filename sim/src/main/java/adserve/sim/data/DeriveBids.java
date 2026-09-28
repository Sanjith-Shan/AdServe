package adserve.sim.data;

import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.sim.Results;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code sim derive-bids}: rescans the replay day's impression log and rewrites only
 * {@code cpcBidMicros} in existing campaign catalogues (see {@link BidDerivation}), leaving request
 * files and every other campaign field alone. Also picks the auction reserve from the day's slot
 * floor prices, writes it to {@code data/work/auction.json}, and writes the M0 bid table to
 * {@code results/m0_bids.jsonl} and {@code results/m0_bids.md}.
 *
 * <p>Usage: {@code derive-bids [--raw data/raw] [--day 20130611] [--auction data/work/auction.json]
 * [--no-results] [campaigns.json ...]} (default catalogues: data/work and data/sample).
 */
public final class DeriveBids {

    public static void main(String[] args) throws IOException {
        Path raw = Path.of("data/raw");
        String day = "20130611";
        Path auction = Path.of("data/work/auction.json");
        boolean results = true;
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--raw" -> raw = Path.of(args[++i]);
                case "--day" -> day = args[++i];
                case "--auction" -> auction = Path.of(args[++i]);
                case "--no-results" -> results = false;
                default -> files.add(Path.of(args[i]));
            }
        }
        if (files.isEmpty()) files = List.of(Path.of("data/work/campaigns.json"), Path.of("data/sample/campaigns.json"));
        String replayDay = day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6);

        BidDerivation d = new BidDerivation();
        Map<String, long[]> advSpend = new TreeMap<>(); // advertiser -> {impressions, micros}
        Map<String, BidDerivation.Histogram> advPay = new TreeMap<>();
        long[] payBelowFloor = {0};
        long rows = IpinyouRow.read(raw.resolve("imp." + day + ".txt.bz2"), r -> {
            d.add(r);
            long[] a = advSpend.computeIfAbsent(r.advertiser(), k -> new long[2]);
            a[0]++;
            a[1] += Mapping.microsPerImpression(r.payPrice());
            advPay.computeIfAbsent(r.advertiser(), k -> new BidDerivation.Histogram()).add(r.payPrice());
            if (r.payPrice() < r.slotPrice()) payBelowFloor[0]++;
        });

        BidDerivation.Histogram floors = d.floors();
        long reserve = BidDerivation.micros(floors.median());
        BidDerivation.LogNormal ln = d.priceModel();
        System.out.printf(Locale.ROOT, "%s: %,d impression rows, reserve %d micros, log-normal mu %.4f sigma %.4f%n",
                replayDay, rows, reserve, ln.mu(), ln.sigma());

        List<Row> table = null;
        for (Path f : files) {
            // Edit the JSON tree in place rather than round-tripping through Campaign: TargetingSpec
            // holds Set.copyOf sets whose iteration order is salted per JVM, so a round trip would
            // reorder every geo list. This way only cpcBidMicros changes, byte for byte.
            ArrayNode tree = (ArrayNode) CampaignFiles.mapper().readTree(f.toFile());
            List<Row> rowsForFile = new ArrayList<>();
            for (JsonNode node : tree) {
                Campaign c = CampaignFiles.mapper().treeToValue(node, Campaign.class);
                CreativeSpec cr = c.creatives().get(0);
                BidDerivation.Bid b = d.bid(c.id(), cr.clickRate());
                ((ObjectNode) node).put("cpcBidMicros", b.cpcBidMicros());
                rowsForFile.add(new Row(c, cr, b));
            }
            CampaignFiles.mapper().writeValue(f.toFile(), tree);
            long fb = rowsForFile.stream().filter(x -> x.bid.fallback()).count();
            System.out.printf(Locale.ROOT, "rewrote %s: %d campaigns, %d fallback%n", f, tree.size(), fb);
            if (table == null) table = rowsForFile;
        }

        ObjectNode a = CampaignFiles.mapper().createObjectNode();
        a.put("reserve_micros", reserve);
        a.put("source", String.format(Locale.ROOT,
                "median slot floor price (slotprice) over all %,d impression rows of iPinYou imp.%s, "
                        + "CPM in fen x 10 = micros per impression; see results/m0_bids.md", rows, day));
        a.put("replay_day", replayDay);
        ObjectNode lnNode = a.putObject("price_lognormal");
        lnNode.put("mu", ln.mu());
        lnNode.put("sigma", ln.sigma());
        lnNode.put("units", "ln(micros per impression), fitted to every positive paying price of the replay day");
        Files.createDirectories(auction.toAbsolutePath().getParent());
        CampaignFiles.mapper().writeValue(auction.toFile(), a);

        if (results && table != null) writeResults(replayDay, rows, d, table, advSpend, advPay, reserve, payBelowFloor[0]);
    }

    record Row(Campaign c, CreativeSpec cr, BidDerivation.Bid bid) {
        long before30() {
            return adserve.core.model.Pricing.impressionValueMicros(c.cpcBidMicros(), cr.clickRate(), 30);
        }
    }

    static void writeResults(String replayDay, long rows, BidDerivation d, List<Row> table,
                             Map<String, long[]> advSpend, Map<String, BidDerivation.Histogram> advPay,
                             long reserve, long payBelowFloor) throws IOException {
        String file = "m0_bids.jsonl";
        for (Row r : table) {
            BidDerivation.Bid b = r.bid;
            ObjectNode n = Results.line("m0-bids");
            n.put("row", "campaign");
            n.put("replay_day", replayDay);
            n.put("campaign", r.c.id());
            n.put("advertiser", r.c.advertiserId());
            n.put("impressions", b.impressions());
            n.put("median_price_micros", b.medianPriceMicros());
            n.put("p10_price_micros", b.p10PriceMicros());
            n.put("p90_price_micros", b.p90PriceMicros());
            n.put("log_median_bidprice_micros", b.medianLogBidMicros());
            n.put("click_rate", b.clickRate());
            n.put("duration_s", r.cr.durationS());
            n.put("cpc_bid_micros", b.cpcBidMicros());
            n.put("bid_value_30s_micros", b.valueMicros(30));
            n.put("bid_value_own_duration_micros", b.valueMicros(r.cr.durationS()));
            n.put("cpc_bid_before_micros", r.c.cpcBidMicros());
            n.put("bid_value_30s_before_micros", r.before30());
            n.put("fallback", b.fallback());
            Results.append(file, n);
        }

        BidDerivation.Histogram floors = d.floors();
        BidDerivation.Histogram pay = d.allPay();
        BidDerivation.LogNormal ln = d.priceModel();
        int reserveFen = (int) (reserve / 10);
        long below30 = table.stream().filter(r -> r.bid.valueMicros(30) < reserve).count();
        long belowOwn = table.stream().filter(r -> r.bid.valueMicros(r.cr.durationS()) < reserve).count();
        long fallbacks = table.stream().filter(r -> r.bid.fallback()).count();

        ObjectNode s = Results.line("m0-bids");
        s.put("row", "summary");
        s.put("replay_day", replayDay);
        s.put("impression_rows", rows);
        s.put("campaigns", table.size());
        s.put("fallback_campaigns", fallbacks);
        s.put("min_observed_prices", BidDerivation.MIN_OBSERVED);
        s.put("bid_rule", "cpc = round(median paying price per impression of the campaign on the replay day / its smoothed click rate)");
        ObjectNode l = s.putObject("price_lognormal");
        l.put("mu", ln.mu());
        l.put("sigma", ln.sigma());
        l.put("median_micros", ln.median());
        l.put("mean_micros", ln.mean());
        l.put("zero_price_share", pay.shareAt(0));
        s.put("reserve_micros", reserve);
        s.put("reserve_rule", "median slot floor price of the replay day");
        s.set("floor_quantiles_micros", quantiles(floors));
        s.put("floor_zero_share", floors.shareAt(0));
        s.set("pay_quantiles_micros", quantiles(pay));
        s.put("impressions_pay_below_reserve_share", pay.shareBelow(reserveFen));
        s.put("impressions_pay_below_own_floor_share", (double) payBelowFloor / Math.max(1, rows));
        s.put("campaigns_30s_value_below_reserve", below30);
        s.put("campaigns_own_duration_value_below_reserve", belowOwn);
        s.put("log_median_bidprice_micros", BidDerivation.micros(d.allLogBid().median()));
        ArrayNode adv = s.putArray("advertisers");
        for (Adv x : advertisers(table, advSpend, advPay)) {
            ObjectNode o = adv.addObject();
            o.put("advertiser", x.id);
            o.put("campaigns", x.campaigns);
            o.put("impressions", x.impressions);
            o.put("median_price_micros", x.medianPrice);
            o.put("avg_price_micros", x.avgPrice);
            o.put("cpc_bid_before_micros", x.cpcBefore);
            o.put("cpc_bid_median_micros", x.cpcAfterMedian);
            o.put("bid_value_30s_before_median_micros", x.before30Median);
            o.put("bid_value_30s_median_micros", x.after30Median);
            o.put("fallback_campaigns", x.fallbacks);
        }
        Results.append(file, s);

        Files.writeString(Path.of("results", "m0_bids.md"),
                markdown(replayDay, rows, table, advertisers(table, advSpend, advPay), floors, pay, ln, reserve,
                        below30, belowOwn, fallbacks, payBelowFloor), StandardCharsets.UTF_8);
    }

    record Adv(String id, int campaigns, long impressions, long medianPrice, long avgPrice, long cpcBefore,
               long cpcAfterMedian, long before30Median, long after30Median, int fallbacks) {}

    static List<Adv> advertisers(List<Row> table, Map<String, long[]> advSpend,
                                 Map<String, BidDerivation.Histogram> advPay) {
        Map<String, List<Row>> by = new TreeMap<>();
        for (Row r : table) by.computeIfAbsent(r.c.advertiserId(), k -> new ArrayList<>()).add(r);
        List<Adv> out = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : by.entrySet()) {
            String raw = e.getKey().startsWith("adv") ? e.getKey().substring(3) : e.getKey();
            long[] sp = advSpend.getOrDefault(raw, new long[2]);
            List<Row> rs = e.getValue();
            out.add(new Adv(e.getKey(), rs.size(), sp[0],
                    BidDerivation.micros(advPay.getOrDefault(raw, new BidDerivation.Histogram()).median()),
                    sp[0] == 0 ? 0 : Math.round((double) sp[1] / sp[0]),
                    median(rs.stream().mapToLong(r -> r.c.cpcBidMicros()).toArray()),
                    median(rs.stream().mapToLong(r -> r.bid.cpcBidMicros()).toArray()),
                    median(rs.stream().mapToLong(Row::before30).toArray()),
                    median(rs.stream().mapToLong(r -> r.bid.valueMicros(30)).toArray()),
                    (int) rs.stream().filter(r -> r.bid.fallback()).count()));
        }
        return out;
    }

    static long median(long[] v) {
        long[] s = v.clone();
        java.util.Arrays.sort(s);
        int n = s.length;
        return n == 0 ? 0 : (s[(n - 1) / 2] + s[n / 2]) / 2;
    }

    static ObjectNode quantiles(BidDerivation.Histogram h) {
        ObjectNode q = CampaignFiles.mapper().createObjectNode();
        for (double p : new double[]{0.10, 0.25, 0.50, 0.75, 0.90, 0.99}) {
            q.put("p" + Math.round(p * 100), BidDerivation.micros(h.quantile(p)));
        }
        return q;
    }

    static String markdown(String replayDay, long rows, List<Row> table, List<Adv> advs,
                           BidDerivation.Histogram floors, BidDerivation.Histogram pay, BidDerivation.LogNormal ln,
                           long reserve, long below30, long belowOwn, long fallbacks, long payBelowFloor) {
        StringBuilder b = new StringBuilder();
        b.append("# M0: bids derived from the replay\n\n");
        b.append(String.format(Locale.ROOT, "Replay day %s (iPinYou season 2, %,d impression rows). Generated by "
                + "`sim derive-bids`; machine-readable rows in `m0_bids.jsonl`. Money is micros of the log's "
                + "currency per impression (paying price CPM in fen x 10).%n%n", replayDay, rows));
        b.append("**Bid rule.** Each campaign's CPC bid is its median paying price per impression on the replay day "
                + "divided by its smoothed click rate, so a 30 s spot at the campaign's own click rate bids exactly "
                + "the median price it really won at. A campaign with fewer than " + BidDerivation.MIN_OBSERVED
                + " observed prices would get a median drawn from the log-normal below, seeded by its id.\n\n");
        b.append(String.format(Locale.ROOT, "**Fallbacks:** %d of %d campaigns.%n%n", fallbacks, table.size()));
        b.append(String.format(Locale.ROOT, "**Price log-normal** (fitted to every positive paying price): mu %.4f, "
                        + "sigma %.4f, so median %.0f and mean %.0f micros. Zero prices: %.2f%% of impressions.%n%n",
                ln.mu(), ln.sigma(), ln.median(), ln.mean(), 100 * pay.shareAt(0)));
        b.append("**Reserve.** " + reserve + " micros per impression per slot, the median slot floor price "
                + "(`slotprice`) of the day. ");
        b.append(String.format(Locale.ROOT, "%.1f%% of slots have no floor at all. %.2f%% of the day's impressions "
                        + "paid less than the reserve. %d of %d campaigns bid below it at 30 s, and %d at their own "
                        + "spot length (15 s spots bid 0.6 of the 30 s value). Paying price below the slot's own "
                        + "floor: %.3f%% of impressions.%n%n",
                100 * floors.shareAt(0), 100 * pay.shareBelow((int) (reserve / 10)), below30, table.size(), belowOwn,
                100.0 * payBelowFloor / Math.max(1, rows)));
        b.append("| Quantile | Slot floor | Paying price |\n|---|---:|---:|\n");
        for (double p : new double[]{0.10, 0.25, 0.50, 0.75, 0.90, 0.99}) {
            b.append(String.format(Locale.ROOT, "| p%d | %d | %d |%n", Math.round(p * 100),
                    BidDerivation.micros(floors.quantile(p)), BidDerivation.micros(pay.quantile(p))));
        }
        b.append("\n## Per advertiser\n\n");
        b.append("\"Before\" is the CPC in the catalogue when derive-bids ran; under the previous rule that was one CPC for the whole advertiser, its average price over its "
                + "average click rate. Values at 30 s are medians over the advertiser's campaigns.\n\n");
        b.append("| Advertiser | Campaigns | Impressions | Median price | Mean price | CPC before | Median CPC after "
                + "| 30 s value before | 30 s value after | Fallbacks |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Adv a : advs) {
            b.append(String.format(Locale.ROOT, "| %s | %d | %,d | %d | %d | %,d | %,d | %d | %d | %d |%n", a.id,
                    a.campaigns, a.impressions, a.medianPrice, a.avgPrice, a.cpcBefore, a.cpcAfterMedian,
                    a.before30Median, a.after30Median, a.fallbacks));
        }
        b.append("\n## Per campaign\n\n");
        b.append("| Campaign | Impressions | p10 price | Median price | p90 price | Log's median bid | Click rate "
                + "| CPC after | 30 s value before | 30 s value after | Spot | Fallback |\n"
                + "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        List<Row> sorted = new ArrayList<>(table);
        sorted.sort(Comparator.comparing((Row r) -> r.c.id()));
        for (Row r : sorted) {
            BidDerivation.Bid x = r.bid;
            b.append(String.format(Locale.ROOT, "| %s | %,d | %d | %d | %d | %d | %.5f | %,d | %d | %d | %d s | %s |%n",
                    r.c.id(), x.impressions(), x.p10PriceMicros(), x.medianPriceMicros(), x.p90PriceMicros(),
                    x.medianLogBidMicros(), x.clickRate(), x.cpcBidMicros(), r.before30(), x.valueMicros(30),
                    r.cr.durationS(), x.fallback() ? "yes" : "no"));
        }
        return b.toString();
    }

    private DeriveBids() {}
}
