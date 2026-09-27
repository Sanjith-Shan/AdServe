package adserve.core.pod;

import java.util.List;

/**
 * Chooses which candidates fill a break and in what order. Every implementation returns either
 * an empty pod or one that passes {@link PodValidator}.
 */
public interface PodSolver {
    String name();

    Pod solve(List<Item> candidates, PodRules rules);
}
