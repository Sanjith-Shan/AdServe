package adserve.core.engine;

/** Supplies the current campaign snapshot. Reads must be a volatile load and nothing more. */
@FunctionalInterface
public interface SnapshotSource {
    CampaignSnapshot current();

    static SnapshotSource fixed(CampaignSnapshot s) {
        return () -> s;
    }
}
