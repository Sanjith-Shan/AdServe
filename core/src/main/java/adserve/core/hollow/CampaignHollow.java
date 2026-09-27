package adserve.core.hollow;

import adserve.core.engine.CampaignSnapshot;
import adserve.core.engine.SnapshotSource;
import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;
import adserve.core.model.FrequencyCap;
import adserve.core.model.PacerKind;
import adserve.core.model.TargetingSpec;
import com.netflix.hollow.api.consumer.HollowConsumer;
import com.netflix.hollow.api.consumer.fs.HollowFilesystemAnnouncementWatcher;
import com.netflix.hollow.api.consumer.fs.HollowFilesystemBlobRetriever;
import com.netflix.hollow.api.objects.generic.GenericHollowObject;
import com.netflix.hollow.api.producer.HollowProducer;
import com.netflix.hollow.api.producer.fs.HollowFilesystemAnnouncer;
import com.netflix.hollow.api.producer.fs.HollowFilesystemPublisher;
import com.netflix.hollow.core.read.engine.HollowReadStateEngine;
import com.netflix.hollow.core.read.engine.object.HollowObjectTypeReadState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The campaign snapshot delivered with Hollow instead of each serving node polling Postgres. One
 * publisher reads the store and runs a producer cycle whenever something changed; Hollow writes a
 * full snapshot the first time and small deltas after. Serving nodes run a consumer that applies
 * each new version in memory and rebuilds the compiled {@link CampaignSnapshot}. The blob store
 * here is a directory; in production it would be an object store, and the serving nodes would
 * never talk to the database at all.
 */
public final class CampaignHollow {
    private CampaignHollow() {}

    public static HollowCampaign toHollow(Campaign c) {
        HollowCampaign h = new HollowCampaign();
        h.id = c.id();
        h.advertiserId = c.advertiserId();
        h.name = c.name();
        h.category = c.category();
        h.cpcBidMicros = c.cpcBidMicros();
        h.dailyBudgetMicros = c.dailyBudgetMicros();
        h.flightStartMs = c.flightStartMs();
        h.flightEndMs = c.flightEndMs();
        h.pacer = c.pacer().name();
        h.capPerDay = c.cap().perDay();
        h.capPerWeek = c.cap().perWeek();
        try {
            h.targetingJson = CampaignFiles.mapper().writer()
                    .without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(c.targeting());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        h.creatives = new ArrayList<>();
        for (CreativeSpec cr : c.creatives()) {
            HollowCreative x = new HollowCreative();
            x.id = cr.id();
            x.durationS = cr.durationS();
            x.clickRate = cr.clickRate();
            h.creatives.add(x);
        }
        h.active = c.active();
        return h;
    }

    static Campaign fromHollow(GenericHollowObject o) {
        List<CreativeSpec> creatives = new ArrayList<>();
        var list = o.getList("creatives");
        for (int i = 0; i < list.size(); i++) {
            GenericHollowObject cr = (GenericHollowObject) list.get(i);
            creatives.add(new CreativeSpec(cr.getString("id"), cr.getInt("durationS"), cr.getDouble("clickRate")));
        }
        TargetingSpec t;
        try {
            t = CampaignFiles.mapper().readValue(o.getString("targetingJson"), TargetingSpec.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Campaign(o.getString("id"), o.getString("advertiserId"), o.getString("name"), o.getString("category"),
                o.getLong("cpcBidMicros"), o.getLong("dailyBudgetMicros"), o.getLong("flightStartMs"),
                o.getLong("flightEndMs"), PacerKind.valueOf(o.getString("pacer")),
                new FrequencyCap(o.getInt("capPerDay"), o.getInt("capPerWeek")), t, creatives, o.getBoolean("active"));
    }

    /** Producer side. */
    public static final class Publisher {
        private final HollowProducer producer;

        public Publisher(Path dir) {
            HollowFilesystemPublisher publisher = new HollowFilesystemPublisher(dir);
            HollowFilesystemAnnouncer announcer = new HollowFilesystemAnnouncer(dir);
            producer = HollowProducer.withPublisher(publisher).withAnnouncer(announcer).build();
            producer.initializeDataModel(HollowCampaign.class);
            producer.restore(announcementVersion(dir), new HollowFilesystemBlobRetriever(dir));
        }

        private static long announcementVersion(Path dir) {
            try {
                return new HollowFilesystemAnnouncementWatcher(dir).getLatestVersion();
            } catch (RuntimeException e) {
                return HollowConsumer.AnnouncementWatcher.NO_ANNOUNCEMENT_AVAILABLE;
            }
        }

        /** Publishes the full campaign set; Hollow writes only a delta if anything changed. Returns the version. */
        public long publish(List<Campaign> campaigns) {
            return producer.runCycle(state -> {
                for (Campaign c : campaigns) state.add(toHollow(c));
            });
        }
    }

    /** Consumer side: a {@link SnapshotSource} fed by Hollow deltas. */
    public static final class Source implements SnapshotSource {
        private final HollowConsumer consumer;
        private volatile CampaignSnapshot snapshot = CampaignSnapshot.empty();
        public final AtomicLong refreshes = new AtomicLong();
        public final AtomicLong deltasApplied = new AtomicLong();
        public final AtomicLong snapshotsApplied = new AtomicLong();
        private volatile long lastRefreshNanos;

        public Source(Path dir, boolean watch) {
            HollowConsumer.Builder<?> b = HollowConsumer.withBlobRetriever(new HollowFilesystemBlobRetriever(dir))
                    .withRefreshListener(new HollowConsumer.AbstractRefreshListener() {
                        @Override
                        public void snapshotApplied(com.netflix.hollow.api.custom.HollowAPI api, HollowReadStateEngine s, long v) {
                            snapshotsApplied.incrementAndGet();
                        }

                        @Override
                        public void deltaApplied(com.netflix.hollow.api.custom.HollowAPI api, HollowReadStateEngine s, long v) {
                            deltasApplied.incrementAndGet();
                        }

                        @Override
                        public void refreshSuccessful(long before, long after, long requested) {
                            rebuild(after);
                        }
                    });
            if (watch) b = b.withAnnouncementWatcher(new HollowFilesystemAnnouncementWatcher(dir));
            consumer = b.build();
            if (watch) consumer.triggerRefresh();
        }

        public void refreshTo(long version) {
            consumer.triggerRefreshTo(version);
        }

        private void rebuild(long version) {
            HollowReadStateEngine state = consumer.getStateEngine();
            HollowObjectTypeReadState type = (HollowObjectTypeReadState) state.getTypeState("HollowCampaign");
            List<Campaign> out = new ArrayList<>();
            if (type != null) {
                BitSet ordinals = type.getPopulatedOrdinals();
                for (int ord = ordinals.nextSetBit(0); ord >= 0; ord = ordinals.nextSetBit(ord + 1)) {
                    out.add(fromHollow(new GenericHollowObject(type, ord)));
                }
            }
            out.sort((a, b) -> a.id().compareTo(b.id()));
            snapshot = new CampaignSnapshot(version, out);
            refreshes.incrementAndGet();
            lastRefreshNanos = System.nanoTime();
        }

        public long lastRefreshNanos() {
            return lastRefreshNanos;
        }

        public long currentVersion() {
            return consumer.getCurrentVersionId();
        }

        @Override
        public CampaignSnapshot current() {
            return snapshot;
        }
    }
}
