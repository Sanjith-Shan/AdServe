package adserve.core.targeting;

import adserve.core.model.Campaign;
import adserve.core.model.Device;
import adserve.core.model.TargetingSpec;

import java.util.List;

/**
 * Compiles every campaign's {@link TargetingSpec} once, when a snapshot is built, and turns each
 * request into a {@link RequestContext} over the same dictionaries.
 */
public final class TargetingIndex {
    private final Dictionary geos = new Dictionary();
    private final Dictionary segments = new Dictionary();
    private final Dictionary genres = new Dictionary();
    private final CompiledTargeting[] compiled;

    public TargetingIndex(List<Campaign> campaigns) {
        // Two passes: intern everything first so every bitset has its final width.
        for (Campaign c : campaigns) {
            TargetingSpec t = c.targeting();
            t.geos().forEach(geos::intern);
            t.segmentsAny().forEach(segments::intern);
            t.excludedGenres().forEach(genres::intern);
        }
        compiled = new CompiledTargeting[campaigns.size()];
        for (int i = 0; i < compiled.length; i++) {
            compiled[i] = compile(campaigns.get(i).targeting());
        }
    }

    private CompiledTargeting compile(TargetingSpec t) {
        long[] g = Bits.of(geos.words());
        t.geos().forEach(s -> Bits.set(g, geos.id(s)));
        int dev = 0;
        for (Device d : t.devices()) dev |= d.bit();
        long[] seg = Bits.of(segments.words());
        t.segmentsAny().forEach(s -> Bits.set(seg, segments.id(s)));
        long[] ex = Bits.of(genres.words());
        t.excludedGenres().forEach(s -> Bits.set(ex, genres.id(s)));
        return new CompiledTargeting(t.geos().isEmpty(), g, dev, t.segmentsAny().isEmpty(), seg, ex);
    }

    public RequestContext context(String geo, String device, Iterable<String> requestSegments, String genre) {
        Device d = Device.parse(device);
        long[] seg = Bits.of(segments.words());
        for (String s : requestSegments) {
            int id = segments.id(s);
            if (id >= 0) Bits.set(seg, id);
        }
        return new RequestContext(geos.id(geo), d == null ? 0 : d.bit(), seg, genres.id(genre));
    }

    public boolean matches(int campaignIndex, RequestContext ctx) {
        return compiled[campaignIndex].matches(ctx);
    }

    public int size() {
        return compiled.length;
    }
}
