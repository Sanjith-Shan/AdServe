package adserve.sim.data;

import ads.v1.Region;

import java.util.Locale;
import java.util.Map;

/**
 * Every place the loader maps an iPinYou field onto something the log does not have. Each rule is
 * deterministic (a hash of a real field), so the same log always produces the same requests, and
 * each is listed in DESIGN.md as an assumption rather than data.
 */
public final class Mapping {
    private Mapping() {}

    /** Advertiser industries from Zhang et al. 2014, Table 1, mapped to competitive-separation categories. */
    public static final Map<String, String> CATEGORY = Map.of(
            "1458", "retail",     // Chinese vertical e-commerce
            "3386", "retail",     // international e-commerce
            "2997", "retail",     // mobile e-commerce app install
            "3358", "software",   // software
            "3427", "auto",       // oil
            "3476", "auto",       // tire
            "2259", "food",       // milk powder
            "2261", "telecom",    // telecom
            "2821", "apparel");   // footwear

    public static final Map<String, String> INDUSTRY = Map.of(
            "1458", "Vertical e-commerce", "3386", "International e-commerce", "2997", "Mobile e-commerce app",
            "3358", "Software", "3427", "Oil", "3476", "Tire", "2259", "Milk powder", "2261", "Telecom",
            "2821", "Footwear");

    static final String[] GENRES = {"drama", "comedy", "reality", "documentary", "kids", "anime", "action",
            "horror", "sports", "news"};
    static final int[] GENRE_WEIGHT = {20, 15, 10, 8, 10, 7, 12, 5, 8, 5};

    /** A stable, well-mixed 64-bit hash of a string (FNV-1a then a murmur finaliser). */
    public static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    static double unit(String s) {
        return (hash(s) >>> 11) * 0x1.0p-53;
    }

    /** The title's genre, from its domain. */
    static String genre(String domain) {
        int total = 0;
        for (int w : GENRE_WEIGHT) total += w;
        int x = (int) Math.floorMod(hash("genre:" + domain), (long) total);
        for (int i = 0; i < GENRES.length; i++) {
            x -= GENRE_WEIGHT[i];
            if (x < 0) return GENRES[i];
        }
        return GENRES[0];
    }

    /** Break length from the bid id: 30 s 10%, 60 s 25%, 90 s 45%, 120 s 20%. */
    static int breakLength(String bidId) {
        double u = unit("break:" + bidId);
        if (u < 0.10) return 30;
        if (u < 0.35) return 60;
        if (u < 0.80) return 90;
        return 120;
    }

    /**
     * Device class from the user agent. Phones and tablets are mobile; the rest are desktop
     * browsers and video clients, of which a stable 40% of viewers are relabelled tv because the
     * 2013 log has no smart TVs.
     */
    static String device(String userAgent, String viewer) {
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("android") || ua.contains("iphone") || ua.contains("ipad") || ua.contains("mobile")) {
            return "mobile";
        }
        return unit("tv:" + viewer) < 0.4 ? "tv" : "web";
    }

    /** Serving region from the iPinYou region code, so a geography always lands in one region. */
    static Region servingRegion(String regionCode) {
        return (hash("dc:" + regionCode) & 1) == 0 ? Region.US_EAST : Region.US_WEST;
    }

    /** Spot length per creative: 15 s 35%, 30 s 50%, 60 s 15%. */
    static int duration(String creative) {
        double u = unit("dur:" + creative);
        if (u < 0.35) return 15;
        if (u < 0.85) return 30;
        return 60;
    }

    /** Frequency cap per campaign: 2, 3 or 4 a day (week = 3x day), or uncapped for a quarter. */
    static int[] cap(String creative) {
        int k = (int) Math.floorMod(hash("cap:" + creative), 4L);
        return switch (k) {
            case 0 -> new int[]{2, 6};
            case 1 -> new int[]{3, 9};
            case 2 -> new int[]{4, 12};
            default -> new int[]{0, 0};
        };
    }

    /** iPinYou paying price is CPM in fen (0.01 CNY); one impression in micro-CNY is price * 10. */
    static long microsPerImpression(int payPrice) {
        return payPrice * 10L;
    }
}
