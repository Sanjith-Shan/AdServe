package adserve.sim.data;

import ads.v1.AdRequest;
import ads.v1.Priority;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.Device;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import adserve.core.caps.Windows;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns two days of iPinYou logs into AdServe's inputs:
 * <ul>
 *   <li>every impression row of each day becomes one {@link AdRequest} (one ad break), written in
 *       arrival order to {@code requests-<day>.bin};</li>
 *   <li>every (advertiser, creative) pair seen on the replay day becomes a campaign whose daily
 *       budget is what that creative actually paid that day, whose bid is its median winning price
 *       that day ({@link BidDerivation}), whose targeting is derived from the
 *       regions, devices and user tags of its real impressions, and whose click rate comes from the
 *       click logs.</li>
 * </ul>
 * The previous day is written too, as the forecast the pacers plan against.
 */
public final class IpinyouLoader {

    static final class CreativeStats {
        final String advertiser;
        final String creative;
        long impressions;
        long spendMicros;
        long clicks;
        final Map<String, Long> regions = new HashMap<>();
        final Map<String, Long> devices = new HashMap<>();
        final Map<String, Long> tags = new HashMap<>();
        long taggedImpressions;

        CreativeStats(String advertiser, String creative) {
            this.advertiser = advertiser;
            this.creative = creative;
        }
    }

    public static void main(String[] args) throws IOException {
        Path raw = Path.of(args.length > 0 ? args[0] : "data/raw");
        Path out = Path.of(args.length > 1 ? args[1] : "data/work");
        String day = args.length > 2 ? args[2] : "20130611";
        String prev = args.length > 3 ? args[3] : "20130610";
        run(raw, out, day, prev);
    }

    public static void run(Path raw, Path out, String day, String prev) throws IOException {
        Files.createDirectories(out);
        Set<String> clicked = new HashSet<>();
        Map<String, Long> clicksByCreative = new HashMap<>();
        for (String d : List.of(day, prev)) {
            IpinyouRow.read(raw.resolve("clk." + d + ".txt.bz2"), r -> {
                clicked.add(r.bidId());
                clicksByCreative.merge(r.advertiser() + "/" + r.creative(), 1L, Long::sum);
            });
        }

        Map<String, CreativeStats> stats = new LinkedHashMap<>();
        Map<String, Long> advImps = new HashMap<>();
        Map<String, Map<String, Long>> advTags = new HashMap<>();
        Map<String, Long> globalTags = new HashMap<>();
        long[] totalTagged = {0};
        long[] total = {0};
        Map<String, Long> impsBothDays = new HashMap<>();

        BidDerivation bids = new BidDerivation();
        long rows;
        try (RequestFiles.Writer w = new RequestFiles.Writer(out.resolve("requests-" + day + ".bin"))) {
            rows = IpinyouRow.read(raw.resolve("imp." + day + ".txt.bz2"), r -> {
                AdRequest req = toRequest(r);
                w.write(req);
                bids.add(r);
                String key = r.advertiser() + "/" + r.creative();
                CreativeStats s = stats.computeIfAbsent(key, k -> new CreativeStats(r.advertiser(), r.creative()));
                s.impressions++;
                s.spendMicros += Mapping.microsPerImpression(r.payPrice());
                s.regions.merge(req.getGeo(), 1L, Long::sum);
                s.devices.merge(req.getDevice(), 1L, Long::sum);
                if (r.userTags().length > 0) {
                    s.taggedImpressions++;
                    totalTagged[0]++;
                }
                for (String t : r.userTags()) {
                    s.tags.merge(t, 1L, Long::sum);
                    advTags.computeIfAbsent(r.advertiser(), k -> new HashMap<>()).merge(t, 1L, Long::sum);
                    globalTags.merge(t, 1L, Long::sum);
                }
                advImps.merge(r.advertiser(), 1L, Long::sum);
                impsBothDays.merge(key, 1L, Long::sum);
                total[0]++;
            });
        }
        long prevRows;
        try (RequestFiles.Writer w = new RequestFiles.Writer(out.resolve("requests-" + prev + ".bin"))) {
            prevRows = IpinyouRow.read(raw.resolve("imp." + prev + ".txt.bz2"), r -> {
                w.write(toRequest(r));
                impsBothDays.merge(r.advertiser() + "/" + r.creative(), 1L, Long::sum);
            });
        }
        clicksByCreative.forEach((k, v) -> {
            CreativeStats s = stats.get(k);
            if (s != null) s.clicks = v;
        });

        // Advertiser-level click rate, the prior each creative's click rate is smoothed toward.
        Map<String, double[]> adv = new HashMap<>(); // {impressions, clicks} over both days
        for (CreativeStats s : stats.values()) {
            double[] a = adv.computeIfAbsent(s.advertiser, k -> new double[2]);
            a[0] += impsBothDays.getOrDefault(s.advertiser + "/" + s.creative, 0L);
            a[1] += s.clicks;
        }

        long dayStart = Windows.day(LocalDay.startMs(day)) * Windows.DAY_MS;
        List<Campaign> campaigns = new ArrayList<>();
        for (CreativeStats s : stats.values()) {
            double[] a = adv.get(s.advertiser);
            double advCtr = a[1] / Math.max(1, a[0]);
            double alpha = 2000;
            long imps2 = impsBothDays.getOrDefault(s.advertiser + "/" + s.creative, 0L);
            double ctr = Math.min(1.0, (s.clicks + alpha * advCtr) / (imps2 + alpha));
            String id = BidDerivation.campaignId(s.advertiser, s.creative);
            // The campaign's median winning price on the day over its own smoothed click rate
            // (a log-normal draw below 30 observed prices): see BidDerivation.
            long cpc = bids.bid(id, ctr).cpcBidMicros();
            int[] cap = Mapping.cap(s.creative);
            campaigns.add(new Campaign(
                    id,
                    "adv" + s.advertiser,
                    Mapping.INDUSTRY.getOrDefault(s.advertiser, "Advertiser") + " " + s.advertiser + " / " + id.substring(id.length() - 4),
                    Mapping.CATEGORY.getOrDefault(s.advertiser, "other"),
                    cpc,
                    s.spendMicros,
                    dayStart,
                    dayStart + Windows.DAY_MS,
                    PacerKind.THROTTLE,
                    new FrequencyCap(cap[0], cap[1]),
                    targeting(s, advTags.get(s.advertiser), advImps.get(s.advertiser), globalTags, total[0]),
                    List.of(new CreativeSpec(s.creative, Mapping.duration(s.creative), ctr)),
                    true));
        }
        campaigns.sort(Comparator.comparing(Campaign::id));
        CampaignFiles.write(out.resolve("campaigns.json"), campaigns);

        ObjectNode report = CampaignFiles.mapper().createObjectNode();
        report.put("replay_day", day);
        report.put("forecast_day", prev);
        report.put("requests_replay_day", rows);
        report.put("requests_forecast_day", prevRows);
        report.put("campaigns", campaigns.size());
        report.put("advertisers", adv.size());
        report.put("clicked_bids", clicked.size());
        report.put("tagged_share", (double) totalTagged[0] / Math.max(1, total[0]));
        CampaignFiles.mapper().writeValue(out.resolve("loader-report.json").toFile(), report);
        System.out.println(report.toPrettyString());
    }

    static AdRequest toRequest(IpinyouRow r) {
        String viewer = r.userId().isEmpty() || r.userId().equals("null") ? "anon-" + r.bidId() : r.userId();
        String title = r.domain().equals("null") || r.domain().isEmpty() ? "slot-" + r.slotId() : r.domain();
        AdRequest.Builder b = AdRequest.newBuilder()
                .setRequestId(r.bidId())
                .setViewerId(viewer)
                .setTitleId(title)
                .setGenre(Mapping.genre(title))
                .setBreakLengthS(Mapping.breakLength(r.bidId()))
                .setDevice(Mapping.device(r.userAgent(), viewer))
                .setRegion(Mapping.servingRegion(r.region()))
                .setPriority(Priority.VOD)
                .setTsMs(r.tsMs())
                .setGeo("r" + r.region());
        for (String t : r.userTags()) b.addSegments("t" + t);
        return b.build();
    }

    /**
     * Targeting from the creative's real impressions: the fewest regions covering 90% of them
     * (any region if that needs more than 20), the device classes with at least 5% share, and the
     * advertiser's lifted user tags (lift at least 1.5 and present on at least 10% of the
     * advertiser's impressions) when they cover at least 60% of this creative's tagged impressions.
     */
    static TargetingSpec targeting(CreativeStats s, Map<String, Long> advTagCounts, long advImpressions,
                                   Map<String, Long> globalTags, long total) {
        List<Map.Entry<String, Long>> regions = new ArrayList<>(s.regions.entrySet());
        regions.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        Set<String> geos = new TreeSet<>();
        long covered = 0;
        for (Map.Entry<String, Long> e : regions) {
            if (covered >= 0.9 * s.impressions) break;
            geos.add(e.getKey());
            covered += e.getValue();
        }
        if (geos.size() > 20) geos.clear();

        Set<Device> devices = new HashSet<>();
        for (Map.Entry<String, Long> e : s.devices.entrySet()) {
            if (e.getValue() >= 0.05 * s.impressions) devices.add(Device.parse(e.getKey()));
        }
        if (devices.size() == Device.values().length) devices.clear();

        Set<String> segs = new TreeSet<>();
        if (advTagCounts != null) {
            for (Map.Entry<String, Long> e : advTagCounts.entrySet()) {
                double advShare = (double) e.getValue() / advImpressions;
                double globalShare = (double) globalTags.getOrDefault(e.getKey(), 0L) / total;
                if (advShare >= 0.10 && globalShare > 0 && advShare / globalShare >= 1.5) segs.add(e.getKey());
            }
        }
        long withSeg = 0;
        for (String t : segs) withSeg = Math.max(withSeg, s.tags.getOrDefault(t, 0L));
        Set<String> segments = new TreeSet<>();
        if (!segs.isEmpty() && s.taggedImpressions > 0 && withSeg >= 0.6 * s.taggedImpressions) {
            for (String t : segs) segments.add("t" + t);
        }
        return new TargetingSpec(geos, devices, segments, Set.of());
    }

    static final class LocalDay {
        static long startMs(String yyyymmdd) {
            return java.time.LocalDate.parse(yyyymmdd, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        }
    }
}
