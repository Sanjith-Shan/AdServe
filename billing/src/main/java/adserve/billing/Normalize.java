package adserve.billing;

import ads.v1.Beacon;
import ads.v1.DecisionRecord;
import ads.v1.EventType;
import ads.v1.Impression;
import ads.v1.Token;
import adserve.core.caps.EventIds;
import adserve.core.token.TokenCodec;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.metrics.Counter;
import org.apache.flink.util.Collector;

/** The Normalize stage for both inputs. */
public final class Normalize {
    private Normalize() {}

    /** ad.responses: one context per served impression. */
    public static final class Decisions extends RichFlatMapFunction<byte[], ImpressionContext> {
        @Override
        public void flatMap(byte[] value, Collector<ImpressionContext> out) throws Exception {
            DecisionRecord d = DecisionRecord.parseFrom(value);
            for (Impression i : d.getResponse().getPodList()) {
                out.collect(new ImpressionContext(i.getImpressionId(), i.getCreative().getCampaignId(),
                        i.getCreative().getCreativeId(), d.getResponse().getViewerId(), i.getPriceMicros(),
                        d.getResponse().getDecidedTsMs(), d.getResponse().getServingRegion().name()));
            }
        }
    }

    /**
     * ad.beacons.*: verify the token, keep the billable IMPRESSION events, and give each its stable
     * event id. The token's serving region travels along, so a beacon that arrived in the wrong
     * region is recognised (and would be forwarded) instead of joined against a replica.
     */
    public static final class Beacons extends RichFlatMapFunction<byte[], BeaconEvent> {
        private transient TokenCodec tokens;
        private transient Counter badTokens;

        @Override
        public void open(OpenContext ctx) {
            tokens = TokenCodec.fromEnv();
            badTokens = getRuntimeContext().getMetricGroup().counter("badTokens");
        }

        @Override
        public void flatMap(byte[] value, Collector<BeaconEvent> out) throws Exception {
            Beacon b = Beacon.parseFrom(value);
            if (b.getType() != EventType.IMPRESSION) return;
            Token t;
            try {
                t = tokens.decode(b.getToken());
            } catch (TokenCodec.InvalidTokenException e) {
                badTokens.inc();
                return;
            }
            out.collect(new BeaconEvent(EventIds.impression(t.getImpressionId()), t.getImpressionId(),
                    b.getTypeValue(), 0, b.getClientTsMs(), b.getArrivalRegion().name(), t.getServingRegion().name()));
        }
    }
}
