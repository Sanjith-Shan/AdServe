package adserve.core.caps;

import ads.v1.EventType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The stable event id: sha256(impression_id | type | offset_s), hex. A retried beacon and the
 * decision-time optimistic write of the same impression produce the same id, which is what makes
 * the counters idempotent. The client's beacon_id is not used because it is not unique across
 * devices.
 */
public final class EventIds {
    private EventIds() {}

    public static String of(String impressionId, EventType type, int offsetS) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((impressionId + "|" + type.getNumber() + "|" + offsetS)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The id of an IMPRESSION event, which is what the frequency counters count. */
    public static String impression(String impressionId) {
        return of(impressionId, EventType.IMPRESSION, 0);
    }
}
