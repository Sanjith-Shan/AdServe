package adserve.core.engine;

import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.DecisionRecord;
import ads.v1.Impression;
import ads.v1.Priority;
import adserve.core.auction.AuctionConfig;
import adserve.core.auction.PricingRule;
import adserve.core.caps.CapCounts;
import adserve.core.caps.CapMode;
import adserve.core.caps.CapStore;
import adserve.core.caps.CapStoreUnavailableException;
import adserve.core.caps.InMemoryCapStore;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionEngineTest {
    static final long T0 = 1_370_908_800_000L; // 2013-06-11 00:00 UTC

    static Campaign c(String id, String adv, String category, int capPerDay, long budget) {
        return new Campaign(id, adv, id, category, 50_000_000, budget, 0, Long.MAX_VALUE, PacerKind.UNPACED,
                new FrequencyCap(capPerDay, 0), TargetingSpec.ANY,
                List.of(new CreativeSpec(id + "-15", 15, 0.01), new CreativeSpec(id + "-30", 30, 0.01)), true);
    }

    static AdRequest req(String viewer, String genre, long ts) {
        return AdRequest.newBuilder().setRequestId("r-" + ts).setViewerId(viewer).setTitleId("t")
                .setGenre(genre).setBreakLengthS(90).setDevice("tv").setPriority(Priority.VOD).setTsMs(ts)
                .setGeo("r1").build();
    }

    final CampaignSnapshot snap = new CampaignSnapshot(1, List.of(
            c("auto1", "A", "auto", 2, Long.MAX_VALUE / 4),
            c("auto2", "B", "auto", 0, Long.MAX_VALUE / 4),
            c("shop", "C", "retail", 0, Long.MAX_VALUE / 4)));

    @Test
    void servesAValidPodWithTokensAndLogsIt() {
        List<DecisionRecord> logged = new ArrayList<>();
        DecisionEngine e = Engines.engine(snap, new InMemoryCapStore(true), EngineConfig.defaults(), logged::add);
        AdResponse r = e.decide(req("v1", "drama", T0)).response();
        assertThat(r.getPodCount()).isEqualTo(3);
        assertThat(r.getPodList()).extracting(i -> i.getCreative().getAdvertiserId()).doesNotHaveDuplicates();
        for (int i = 1; i < r.getPodCount(); i++) {
            assertThat(r.getPod(i).getCreative().getCategory()).isNotEqualTo(r.getPod(i - 1).getCreative().getCategory());
        }
        assertThat(r.getPodList()).allSatisfy(i -> assertThat(i.getToken()).isNotBlank());
        assertThat(r.getPodList().stream().mapToInt(i -> i.getCreative().getDurationS()).sum()).isLessThanOrEqualTo(90);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).getResponse()).isEqualTo(r);
        assertThat(e.invalidPods()).isZero();
    }

    @Test
    void frequencyCapStopsTheThirdImpression() {
        DecisionEngine e = Engines.engine(snap, new InMemoryCapStore(true), EngineConfig.defaults(), DecisionLog.NONE);
        int served = 0;
        for (int i = 0; i < 5; i++) {
            AdResponse r = e.decide(req("v1", "drama", T0 + i * 1000L)).response();
            served += (int) r.getPodList().stream().filter(p -> p.getCreative().getCampaignId().equals("auto1")).count();
        }
        assertThat(served).isEqualTo(2);
    }

    @Test
    void brandSafetyKeepsAutoOutOfKidsTitles() {
        DecisionEngine e = Engines.engine(snap, new InMemoryCapStore(true), EngineConfig.defaults(), DecisionLog.NONE);
        AdResponse r = e.decide(req("v1", "kids", T0)).response();
        assertThat(r.getPodList()).extracting(i -> i.getCreative().getCategory()).containsOnly("retail");
    }

    @Test
    void budgetStopsServing() {
        // A lone bidder clears at the reserve under second price, so the reserve is what spends it.
        CampaignSnapshot tiny = new CampaignSnapshot(1, List.of(c("shop", "C", "retail", 0, 400_000)));
        EngineConfig cfg = EngineConfig.defaults().withAuction(AuctionConfig.defaults().withReserve(100_000));
        DecisionEngine e = Engines.engine(tiny, new InMemoryCapStore(true), cfg, DecisionLog.NONE);
        long spent = 0;
        for (int i = 0; i < 10; i++) {
            spent += e.decide(req("v" + i, "drama", T0 + i)).response().getPodList().stream()
                    .mapToLong(Impression::getPriceMicros).sum();
        }
        assertThat(spent).isLessThanOrEqualTo(400_000).isGreaterThan(0);
    }

    @Test
    void capModeDecidesWhatHappensWhenTheCounterStoreIsDown() {
        CapStore down = new CapStore() {
            public CapCounts fetch(String v, List<String> ids, long now) {
                throw new CapStoreUnavailableException("down", null);
            }

            public void recordImpression(String v, String c, String e, long ts) {}
        };
        DecisionEngine allow = Engines.engine(snap, down, EngineConfig.defaults().withCapMode(CapMode.UNKNOWN_ALLOW), DecisionLog.NONE);
        DecisionEngine deny = Engines.engine(snap, down, EngineConfig.defaults().withCapMode(CapMode.UNKNOWN_DENY), DecisionLog.NONE);
        assertThat(allow.decide(req("v", "drama", T0)).response().getPodList())
                .extracting(i -> i.getCreative().getCampaignId()).contains("auto1");
        var denied = deny.decide(req("v", "drama", T0));
        assertThat(denied.response().getPodList()).extracting(i -> i.getCreative().getCampaignId())
                .doesNotContain("auto1").isNotEmpty();
        assertThat(denied.record().getCapMode()).isEqualTo("unknown_deny");
    }

    @Test
    void theDecisionLogCarriesClearedPrices() {
        List<DecisionRecord> logged = new ArrayList<>();
        EngineConfig cfg = EngineConfig.defaults().withAuction(AuctionConfig.defaults().withReserve(1_000));
        DecisionEngine e = Engines.engine(snap, new InMemoryCapStore(true), cfg, logged::add);
        AdResponse r = e.decide(req("v1", "drama", T0)).response();
        DecisionRecord rec = logged.get(0);
        assertThat(rec.getPricing()).isEqualTo("second_price");
        assertThat(rec.getReserveMicros()).isEqualTo(1_000);
        assertThat(rec.getPodClearedMicros()).isEqualTo(r.getPodList().stream().mapToLong(Impression::getPriceMicros).sum());
        assertThat(r.getPodList()).allSatisfy(i -> {
            assertThat(i.getPriceMicros()).isBetween(1_000L, i.getBidMicros());
            assertThat(i.getScoreMicros()).isEqualTo(i.getBidMicros());
        });
        assertThat(rec.getPodClearedMicros()).isLessThanOrEqualTo(rec.getPodValueMicros());
    }

    @Test
    void secondPriceChargesTheBudgetLessThanFirstPrice() {
        EngineConfig second = EngineConfig.defaults().withAuction(AuctionConfig.defaults().withReserve(1_000));
        EngineConfig first = second.withAuction(second.auction().withPricing(PricingRule.FIRST_PRICE));
        DecisionEngine a = Engines.engine(snap, new InMemoryCapStore(false), second, DecisionLog.NONE);
        DecisionEngine b = Engines.engine(snap, new InMemoryCapStore(false), first, DecisionLog.NONE);
        AdResponse ra = a.decide(req("v1", "drama", T0)).response();
        AdResponse rb = b.decide(req("v1", "drama", T0)).response();
        assertThat(rb.getPodList()).allSatisfy(i -> assertThat(i.getPriceMicros()).isEqualTo(i.getBidMicros()));
        long spentA = a.budget().spent("auto1", T0 / 86_400_000L) + a.budget().spent("auto2", T0 / 86_400_000L)
                + a.budget().spent("shop", T0 / 86_400_000L);
        long spentB = b.budget().spent("auto1", T0 / 86_400_000L) + b.budget().spent("auto2", T0 / 86_400_000L)
                + b.budget().spent("shop", T0 / 86_400_000L);
        assertThat(spentA).isEqualTo(ra.getPodList().stream().mapToLong(Impression::getPriceMicros).sum());
        assertThat(spentB).isEqualTo(rb.getPodList().stream().mapToLong(Impression::getBidMicros).sum());
        assertThat(spentA).isLessThanOrEqualTo(spentB);
    }
}
