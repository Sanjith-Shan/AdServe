package adserve.core.token;

import ads.v1.Token;
import com.google.protobuf.InvalidProtocolBufferException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * The opaque impression token: base64url(Token bytes || HMAC-SHA256(Token bytes)). The device
 * carries it and returns it on every beacon; the beacon consumer verifies the MAC with the shared
 * key and reads the serving region to route the beacon. The token is signed, not encrypted: the
 * device can read it but cannot forge or alter it.
 */
public final class TokenCodec {
    private static final int MAC_LEN = 32;
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final Mac prototype;

    public TokenCodec(byte[] key) {
        if (key.length < 32) throw new IllegalArgumentException("token key must be at least 32 bytes");
        try {
            prototype = Mac.getInstance("HmacSHA256");
            prototype.init(new SecretKeySpec(key, "HmacSHA256"));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the key from ADS_TOKEN_KEY, or uses a fixed development key and says so. */
    public static TokenCodec fromEnv() {
        String k = System.getenv("ADS_TOKEN_KEY");
        if (k == null || k.isBlank()) {
            k = "adserve-development-key-not-for-production-use";
        }
        return new TokenCodec(k.getBytes(StandardCharsets.UTF_8));
    }

    private Mac mac() {
        try {
            return (Mac) prototype.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    public String encode(Token t) {
        byte[] body = t.toByteArray();
        byte[] sig = mac().doFinal(body);
        byte[] out = Arrays.copyOf(body, body.length + MAC_LEN);
        System.arraycopy(sig, 0, out, body.length, MAC_LEN);
        return ENC.encodeToString(out);
    }

    /** Verifies and decodes. Throws {@link InvalidTokenException} on any tampering or garbage. */
    public Token decode(String token) {
        byte[] raw;
        try {
            raw = DEC.decode(token);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("not base64url");
        }
        if (raw.length <= MAC_LEN) throw new InvalidTokenException("too short");
        byte[] body = Arrays.copyOf(raw, raw.length - MAC_LEN);
        byte[] sig = Arrays.copyOfRange(raw, raw.length - MAC_LEN, raw.length);
        if (!MessageDigest.isEqual(sig, mac().doFinal(body))) throw new InvalidTokenException("bad signature");
        try {
            return Token.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new InvalidTokenException("bad body");
        }
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String m) {
            super(m);
        }
    }
}
