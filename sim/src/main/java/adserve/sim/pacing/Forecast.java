package adserve.sim.pacing;

import ads.v1.AdRequest;
import adserve.core.engine.CampaignSnapshot;
import adserve.core.io.CampaignFiles;
import adserve.core.io.RequestFiles;
import adserve.core.model.Campaign;
import adserve.core.policy.BrandSafety;
import adserve.core.targeting.RequestContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The pacers' forecast: for every campaign, how many of the previous day's ad breaks it would
 * have been eligible for (targeting and brand safety) in each one-minute slot, smoothed with a
 * centred 15-minute moving average. Key "*" holds the all-request curve for campaigns created
 * later. Written to data/work/forecast.json, which the server reads too.
 */
public final class Forecast {
    public static final int SLOTS = 1440;

    public static void main(String[] a) throws IOException {
        Path campaigns = Path.of(a.length > 0 ? a[0] : "data/work/campaigns.json");
        Path prevDay = Path.of(a.length > 1 ? a[1] : "data/work/requests-20130610.bin");
        Path out = Path.of(a.length > 2 ? a[2] : "data/work/forecast.json");
        Map<String, double[]> f = build(CampaignFiles.read(campaigns), prevDay);
        CampaignFiles.mapper().writeValue(out.toFile(), f);
        System.out.println("wrote " + out + " for " + (f.size() - 1) + " campaigns");
    }

    public static Map<String, double[]> build(List<Campaign> cs, Path prevDay) throws IOException {
        CampaignSnapshot snap = new CampaignSnapshot(0, cs);
        BrandSafety bs = BrandSafety.defaults();
        double[][] counts = new double[cs.size()][SLOTS];
        double[] all = new double[SLOTS];
        RequestFiles.forEach(prevDay, r -> {
            int slot = (int) (Math.floorMod(r.getTsMs(), 86_400_000L) / 60_000L);
            all[slot]++;
            RequestContext ctx = snap.targeting().context(r.getGeo(), r.getDevice(), r.getSegmentsList(), r.getGenre());
            for (int i = 0; i < cs.size(); i++) {
                if (snap.targeting().matches(i, ctx) && bs.allowed(cs.get(i).category(), r.getGenre())) counts[i][slot]++;
            }
        });
        Map<String, double[]> out = new LinkedHashMap<>();
        out.put("*", smooth(all));
        for (int i = 0; i < cs.size(); i++) out.put(cs.get(i).id(), smooth(counts[i]));
        return out;
    }

    static double[] smooth(double[] x) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            double s = 0;
            int n = 0;
            for (int k = -7; k <= 7; k++) {
                int j = i + k;
                if (j < 0 || j >= x.length) continue;
                s += x[j];
                n++;
            }
            y[i] = s / n + 1e-6;
        }
        return y;
    }
}
