package adserve.server.campaigns;

import adserve.core.hollow.CampaignHollow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Reads the campaign store and runs a Hollow producer cycle every refresh interval. Hollow only
 * writes a new version (a delta) when the data changed. Run it on one node; every serving node
 * consumes the blobs.
 */
public class HollowPublisherTask {
    private static final Logger log = LoggerFactory.getLogger(HollowPublisherTask.class);
    private final CampaignHollow.Publisher publisher;
    private final CampaignRepository repo;
    private volatile long lastVersion;

    public HollowPublisherTask(CampaignHollow.Publisher publisher, CampaignRepository repo) {
        this.publisher = publisher;
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "${adserve.snapshot-refresh-ms}")
    public void publish() {
        try {
            long v = publisher.publish(repo.loadAll());
            if (v != lastVersion) log.info("published campaign snapshot version {}", v);
            lastVersion = v;
        } catch (RuntimeException e) {
            log.warn("hollow publish failed, serving nodes keep their current version: {}", e.getMessage());
        }
    }
}
