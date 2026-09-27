package adserve.core.token;

import ads.v1.Region;
import ads.v1.Token;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCodecTest {
    private final TokenCodec codec = new TokenCodec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final Token token = Token.newBuilder().setImpressionId("imp1").setCampaignId("c1")
            .setCreativeId("cr1").setServingRegion(Region.US_WEST).setIssuedTsMs(42).build();

    @Test
    void roundTrips() {
        assertThat(codec.decode(codec.encode(token))).isEqualTo(token);
    }

    @Test
    void rejectsATamperedBody() {
        byte[] raw = Base64.getUrlDecoder().decode(codec.encode(token));
        raw[3] ^= 1;
        String bad = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        assertThatThrownBy(() -> codec.decode(bad)).isInstanceOf(TokenCodec.InvalidTokenException.class);
    }

    @Test
    void rejectsAnotherKeysToken() {
        TokenCodec other = new TokenCodec("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(other.encode(token))).isInstanceOf(TokenCodec.InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> codec.decode("!!!")).isInstanceOf(TokenCodec.InvalidTokenException.class);
        assertThatThrownBy(() -> codec.decode("AAAA")).isInstanceOf(TokenCodec.InvalidTokenException.class);
    }
}
