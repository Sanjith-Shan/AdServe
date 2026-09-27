package adserve.server;

import ads.v1.AdDecisionGrpc;
import ads.v1.AdRequest;
import ads.v1.AdResponse;
import ads.v1.DecisionRecord;
import ads.v1.Priority;
import ads.v1.Region;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import adserve.core.token.TokenCodec;
import adserve.server.campaigns.CampaignCache;
import adserve.server.campaigns.CampaignRepository;
import adserve.server.grpc.GrpcServer;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole serving path against real Postgres, Kafka and Redis: a campaign written to the store
 * is picked up by the snapshot, a gRPC decision returns a valid pod with verifiable tokens, and
 * the decision appears on ad.responses.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {"adserve.grpc-port=0", "server.port=0", "adserve.snapshot-refresh-ms=500"})
class ServingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCopyFileToContainer(MountableFile.forHostPath("../deploy/postgres/init.sql"),
                    "/docker-entrypoint-initdb.d/01-init.sql");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("adserve.kafka.bootstrap", KAFKA::getBootstrapServers);
        r.add("adserve.redis.uri", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired CampaignRepository repo;
    @Autowired CampaignCache cache;
    @Autowired GrpcServer grpc;
    @Autowired com.netflix.graphql.dgs.DgsQueryExecutor graphql;

    static final long T0 = 1_370_950_000_000L;

    static Campaign campaign(String id, String adv, String category) {
        return new Campaign(id, adv, id, category, 50_000_000, 1_000_000_000L, T0 - 86_400_000L, T0 + 86_400_000L,
                PacerKind.UNPACED, new FrequencyCap(3, 9), TargetingSpec.ANY,
                List.of(new CreativeSpec(id + "-a", 30, 0.01), new CreativeSpec(id + "-b", 15, 0.012)), true);
    }

    @Test
    void decidesEndToEndAndLogsTheDecision() throws Exception {
        repo.upsert(campaign("it-auto", "it-adv1", "auto"), "Adv 1");
        repo.upsert(campaign("it-shop", "it-adv2", "retail"), "Adv 2");
        cache.refreshNow();

        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
        try {
            AdRequest req = AdRequest.newBuilder().setRequestId("it-1").setViewerId("viewer-it").setTitleId("t")
                    .setGenre("drama").setBreakLengthS(90).setDevice("tv").setRegion(Region.US_EAST)
                    .setPriority(Priority.LIVE).setTsMs(T0).setGeo("r1").build();
            AdResponse resp = AdDecisionGrpc.newBlockingStub(ch).decide(req);
            assertThat(resp.getPodCount()).isEqualTo(2);
            TokenCodec codec = TokenCodec.fromEnv();
            assertThat(resp.getPodList()).allSatisfy(i ->
                    assertThat(codec.decode(i.getToken()).getImpressionId()).isEqualTo(i.getImpressionId()));

            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
            p.put(ConsumerConfig.GROUP_ID_CONFIG, "it");
            p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            try (KafkaConsumer<String, byte[]> c = new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer())) {
                c.subscribe(Set.of("ad.responses"));
                DecisionRecord found = null;
                long deadline = System.currentTimeMillis() + 30_000;
                while (found == null && System.currentTimeMillis() < deadline) {
                    for (ConsumerRecord<String, byte[]> r : c.poll(Duration.ofMillis(500))) {
                        if (r.key().equals("it-1")) found = DecisionRecord.parseFrom(r.value());
                    }
                }
                assertThat(found).isNotNull();
                assertThat(found.getResponse()).isEqualTo(resp);
                assertThat(found.getRequest()).isEqualTo(req);
            }
        } finally {
            ch.shutdownNow();
        }
    }

    @Test
    void aCampaignCreatedThroughGraphQlIsServedByTheNextDecision() {
        String mutation = """
                mutation {
                  createCampaign(input: {
                    id: "gql-1", advertiserId: "gql-adv", advertiserName: "GraphQL Advertiser", name: "Launch week",
                    category: "software", cpcBidMicros: 90000000, dailyBudgetMicros: 5000000000,
                    startsAt: "2013-06-10", endsAt: "2013-06-13", pacer: UNPACED, capPerDay: 2,
                    targeting: { geos: ["r-only-here"], devices: [TV] },
                    creatives: [{ id: "gql-1-30", durationS: 30, clickRate: 0.02 }]
                  }) { id flight { dailyBudgetMicros } creatives { valueMicros } }
                }""";
        Long budget = graphql.executeAndExtractJsonPath(mutation, "data.createCampaign.flight.dailyBudgetMicros");
        assertThat(budget).isEqualTo(5_000_000_000L);

        ManagedChannel ch = ManagedChannelBuilder.forAddress("localhost", grpc.port()).usePlaintext().build();
        try {
            AdRequest req = AdRequest.newBuilder().setRequestId("gql-req").setViewerId("viewer-gql").setTitleId("t")
                    .setGenre("drama").setBreakLengthS(60).setDevice("tv").setRegion(Region.US_EAST)
                    .setPriority(Priority.VOD).setTsMs(T0).setGeo("r-only-here").build();
            AdResponse resp = AdDecisionGrpc.newBlockingStub(ch).decide(req);
            assertThat(resp.getPodList()).extracting(i -> i.getCreative().getCampaignId()).contains("gql-1");
        } finally {
            ch.shutdownNow();
        }
        Number spend = graphql.executeAndExtractJsonPath(
                "{ campaign(id: \"gql-1\") { delivery { spendMicros } } }", "data.campaign.delivery.spendMicros");
        assertThat(spend.longValue()).isPositive();
    }
}
