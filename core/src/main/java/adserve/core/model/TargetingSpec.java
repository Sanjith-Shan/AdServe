package adserve.core.model;

import java.util.Set;

/**
 * What a campaign asked for, before compilation. An empty set means "any" for geos, devices and
 * segments. {@code excludedGenres} are genres the campaign refuses to run in.
 * A request matches when its geo is in {@code geos}, its device is in {@code devices}, it carries
 * at least one of {@code segmentsAny}, and its title genre is not excluded.
 */
public record TargetingSpec(Set<String> geos, Set<Device> devices, Set<String> segmentsAny,
                            Set<String> excludedGenres) {
    public static final TargetingSpec ANY = new TargetingSpec(Set.of(), Set.of(), Set.of(), Set.of());

    public TargetingSpec {
        geos = Set.copyOf(geos);
        devices = Set.copyOf(devices);
        segmentsAny = Set.copyOf(segmentsAny);
        excludedGenres = Set.copyOf(excludedGenres);
    }
}
