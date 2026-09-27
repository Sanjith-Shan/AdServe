package adserve.core.pod;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PodSolverTest {
    // Two auto advertisers, one retail, one software; a 90-second break.
    private final Item autoA = new Item(0, 0, 0, 0, 30, 900);
    private final Item autoB = new Item(1, 1, 1, 0, 30, 800);
    private final Item retail = new Item(2, 2, 2, 1, 15, 300);
    private final Item software = new Item(3, 3, 3, 2, 60, 1000);

    @Test
    void neverPlaysTwoCarAdsBackToBack() {
        PodRules rules = new PodRules(90, 1, 6, Separation.ADJACENT);
        Pod pod = new ExactSolver().solve(List.of(autoA, autoB, retail, software), rules);
        assertThat(PodValidator.violation(pod, List.of(autoA, autoB, retail, software), rules)).isNull();
        // Best is autoA + autoB + retail (2000) with retail between the car ads.
        assertThat(pod.value()).isEqualTo(2000);
        assertThat(pod.items()).extracting(Item::category).containsExactly(0, 1, 0);
    }

    @Test
    void podSeparationAllowsOneCarAd() {
        PodRules rules = new PodRules(90, 1, 6, Separation.POD);
        Pod pod = new ExactSolver().solve(List.of(autoA, autoB, retail, software), rules);
        assertThat(pod.items()).extracting(Item::category).doesNotHaveDuplicates();
        assertThat(pod.value()).isEqualTo(1900); // software 60s + autoA 30s
    }

    @Test
    void minimumCountIsRepairedOrThePodIsEmpty() {
        PodRules rules = new PodRules(60, 3, 6, Separation.NONE);
        Pod pod = new GreedySolver(false).solve(List.of(software, retail, autoA), rules);
        // software (60s) alone cannot meet a minimum of 3; retail + autoA is only 2 ads.
        assertThat(pod).isEqualTo(Pod.EMPTY);
        Item retail2 = new Item(4, 4, 4, 1, 15, 100);
        pod = new GreedySolver(false).solve(List.of(software, retail, autoA, retail2), rules);
        assertThat(pod.size()).isEqualTo(3);
    }

    @Test
    void relaxedBoundIgnoresAdvertiserRule() {
        Item a1 = new Item(0, 0, 0, 0, 30, 500);
        Item a2 = new Item(1, 1, 0, 0, 30, 500);
        assertThat(DpSolver.relaxedBound(List.of(a1, a2), 60)).isEqualTo(1000);
        assertThat(new DpSolver().solve(List.of(a1, a2), new PodRules(60, 1, 6, Separation.NONE)).value()).isEqualTo(500);
    }
}
