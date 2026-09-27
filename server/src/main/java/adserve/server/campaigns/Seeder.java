package adserve.server.campaigns;

import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.server.config.AdServeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Loads campaigns.json into Postgres at startup when {@code adserve.seed-file} is set. */
@Component
@Order(0)
public class Seeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(Seeder.class);
    private final AdServeProperties props;
    private final CampaignRepository repo;
    private final CampaignCache cache;

    public Seeder(AdServeProperties props, CampaignRepository repo, CampaignCache cache) {
        this.props = props;
        this.repo = repo;
        this.cache = cache;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String f = props.seedFile();
        if (f == null || f.isBlank()) return;
        Path p = Path.of(f);
        if (!Files.exists(p)) {
            log.warn("seed file {} not found", p);
            return;
        }
        List<Campaign> all = CampaignFiles.read(p);
        for (Campaign c : all) repo.upsert(c, c.name().replaceAll(" /.*", ""));
        log.info("seeded {} campaigns from {}", all.size(), p);
        cache.refreshNow();
    }
}
