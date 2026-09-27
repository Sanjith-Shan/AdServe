package adserve.core.policy;

import java.util.Map;
import java.util.Set;

/**
 * Platform-level brand-safety rules: an ad category never runs inside titles of certain genres,
 * whatever the campaign asked for (for example no auto or alcohol ads in kids titles). This is
 * separate from a campaign's own genre exclusions, which live in its targeting.
 */
public final class BrandSafety {
    private final Map<String, Set<String>> bannedGenresByCategory;

    public BrandSafety(Map<String, Set<String>> bannedGenresByCategory) {
        this.bannedGenresByCategory = Map.copyOf(bannedGenresByCategory);
    }

    public static BrandSafety defaults() {
        return new BrandSafety(Map.of(
                "auto", Set.of("kids"),
                "alcohol", Set.of("kids", "family"),
                "software", Set.of(),
                "retail", Set.of()));
    }

    public boolean allowed(String category, String genre) {
        Set<String> banned = bannedGenresByCategory.get(category);
        return banned == null || genre == null || !banned.contains(genre);
    }
}
