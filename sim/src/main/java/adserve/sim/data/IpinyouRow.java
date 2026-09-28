package adserve.sim.data;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * One line of an iPinYou season 2 or 3 impression or click log (tab separated, 24 columns:
 * bidid, timestamp, logtype, ipinyouid, useragent, IP, region, city, adexchange, domain, url,
 * urlid, slotid, slotwidth, slotheight, slotvisibility, slotformat, slotprice, creative, bidprice,
 * payprice, keypage, advertiser, usertags). Field layout from the dataset README and
 * wnzhang/make-ipinyou-data. {@code slotPrice} (the slot's floor), {@code bidPrice} (iPinYou's own
 * bid) and {@code payPrice} (the second price actually paid) are all CPM in fen.
 */
public record IpinyouRow(String bidId, long tsMs, String userId, String userAgent, String region,
                         String domain, String slotId, String creative, int slotPrice, int bidPrice, int payPrice,
                         String advertiser,
                         String[] userTags) {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    static IpinyouRow parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 23) return null;
        long ts = LocalDateTime.parse(f[1], TS).toInstant(ZoneOffset.UTC).toEpochMilli();
        String tags = f.length > 23 ? f[23] : "null";
        String[] t = tags.isEmpty() || tags.equals("null") ? new String[0] : tags.split(",");
        return new IpinyouRow(f[0], ts, f[3], f[4], f[6], f[9], f[12], f[18],
                intOrZero(f[17]), intOrZero(f[19]), intOrZero(f[20]), f[22], t);
    }

    private static int intOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Streams a .txt.bz2 (or plain .txt) log. Returns the number of rows read. */
    public static long read(Path p, Consumer<IpinyouRow> f) throws IOException {
        long n = 0;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(p), 1 << 20);
             InputStream in = p.toString().endsWith(".bz2") ? new BZip2CompressorInputStream(raw) : raw;
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20)) {
            String line;
            while ((line = r.readLine()) != null) {
                IpinyouRow row = parse(line);
                if (row != null) {
                    f.accept(row);
                    n++;
                }
            }
        }
        return n;
    }
}
