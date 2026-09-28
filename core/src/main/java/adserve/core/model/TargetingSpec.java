package adserve.core.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a campaign asked for, before compilation. An empty set means "any" for geos, devices and
 * segments. {@code excludedGenres} are genres the campaign refuses to run in.
 * A request matches when its geo is in {@code geos}, its device is in {@code devices}, it carries
 * at least one of {@code segmentsAny}, and its title genre is not excluded.
 *
 * <p>The sets are sorted, so a campaign file written twice is byte-identical (BUG_LOG bug 13).
 */
public record TargetingSpec(Set<String> geos, Set<Device> devices, Set<String> segmentsAny,
                            Set<String> excludedGenres) {
    public static final TargetingSpec ANY = new TargetingSpec(Set.of(), Set.of(), Set.of(), Set.of());

    public TargetingSpec {
        geos = sorted(geos);
        devices = devices.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(devices));
        segmentsAny = sorted(segmentsAny);
        excludedGenres = sorted(excludedGenres);
    }

    private static Set<String> sorted(Set<String> s) {
        return s.isEmpty() ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(s));
    }
}
