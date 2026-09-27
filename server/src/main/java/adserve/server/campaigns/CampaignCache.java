package adserve.server.campaigns;

import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.SnapshotSource;
import adserve.core.model.Campaign;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The read-through campaign cache: a compiled {@link CampaignSnapshot} rebuilt from Postgres every
 * few seconds and swapped in with one volatile write. When Postgres is down the refresh fails,
 * the last good snapshot keeps serving, and the staleness gauge climbs; the decision path never
 * notices.
 */
@Component
public class CampaignCache implements SnapshotSource {
    private static final Logger log = LoggerFactory.getLogger(CampaignCache.class);

    private final CampaignRepository repo;
    private volatile CampaignSnapshot snapshot = CampaignSnapshot.empty();
    private final AtomicLong version = new AtomicLong();
    private volatile long lastSuccessMs = 0;
    private final AtomicLong failures = new AtomicLong();

    public CampaignCache(CampaignRepository repo, MeterRegistry registry) {
        this.repo = repo;
        Gauge.builder("adserve.snapshot.staleness.seconds", this, c -> c.lastSuccessMs == 0 ? -1
                : (System.currentTimeMillis() - c.lastSuccessMs) / 1000.0).register(registry);
        Gauge.builder("adserve.snapshot.campaigns", this, c -> c.snapshot.size()).register(registry);
        Gauge.builder("adserve.snapshot.refresh.failures", failures, AtomicLong::get).register(registry);
    }

    @Override
    public CampaignSnapshot current() {
        return snapshot;
    }

    @Scheduled(fixedDelayString = "${adserve.snapshot-refresh-ms}", initialDelay = 0)
    public void refresh() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            log.warn("campaign refresh failed, serving snapshot v{} ({} campaigns): {}", snapshot.version(),
                    snapshot.size(), e.getMessage());
        }
    }

    /** Rebuilds from the store now. The management API calls this after a write. */
    public synchronized CampaignSnapshot refreshNow() {
        List<Campaign> all = repo.loadAll();
        snapshot = new CampaignSnapshot(version.incrementAndGet(), all);
        lastSuccessMs = System.currentTimeMillis();
        return snapshot;
    }

    public long lastSuccessMs() {
        return lastSuccessMs;
    }
}
