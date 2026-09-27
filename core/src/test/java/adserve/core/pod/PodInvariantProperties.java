package adserve.core.pod;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No solver ever returns a pod that breaks a rule, the exact solver matches brute force, and the
 * heuristics never beat the exact solver or the relaxed bound.
 */
class PodInvariantProperties {

    private static final List<PodSolver> SOLVERS = List.of(
            new GreedySolver(false), new GreedySolver(true), new DpSolver(), new ExactSolver());

    @Provide
    Arbitrary<Catalogs.Case> small() {
        return Catalogs.cases(8);
    }

    @Provide
    Arbitrary<Catalogs.Case> large() {
        return Catalogs.cases(40);
    }

    @Property(tries = 3000)
    void everyPodSatisfiesEveryRule(@ForAll("large") Catalogs.Case c) {
        for (PodSolver s : SOLVERS) {
            Pod pod = s.solve(c.items(), c.rules());
            assertThat(PodValidator.violation(pod, c.items(), c.rules()))
                    .as("%s on %s", s.name(), c)
                    .isNull();
        }
    }

    @Property(tries = 3000)
    void exactMatchesBruteForce(@ForAll("small") Catalogs.Case c) {
        if (c.items().size() > 14) return;
        ExactSolver exact = new ExactSolver();
        long v = exact.solve(c.items(), c.rules()).value();
        assertThat(exact.lastProvedOptimal()).isTrue();
        assertThat(v).as("%s", c).isEqualTo(Catalogs.bruteForce(c.items(), c.rules()));
    }

    @Property(tries = 2000)
    void heuristicsNeverBeatExactAndNothingBeatsTheRelaxation(@ForAll("large") Catalogs.Case c) {
        ExactSolver exact = new ExactSolver();
        long best = exact.solve(c.items(), c.rules()).value();
        if (!exact.lastProvedOptimal()) return;
        long relaxed = DpSolver.relaxedBound(c.items(), c.rules().capacityS());
        assertThat(best).isLessThanOrEqualTo(relaxed);
        for (PodSolver s : SOLVERS) {
            assertThat(s.solve(c.items(), c.rules()).value()).as(s.name()).isLessThanOrEqualTo(best);
        }
    }

    @Property(tries = 2000)
    void dpIsExactWhenSeparationCannotBind(@ForAll("large") Catalogs.Case c) {
        PodRules r = new PodRules(c.rules().capacityS(), c.rules().minAds(), c.rules().maxAds(), Separation.NONE);
        ExactSolver exact = new ExactSolver();
        long best = exact.solve(c.items(), r).value();
        if (!exact.lastProvedOptimal()) return;
        assertThat(new DpSolver().solve(c.items(), r).value()).isEqualTo(best);
    }
}
