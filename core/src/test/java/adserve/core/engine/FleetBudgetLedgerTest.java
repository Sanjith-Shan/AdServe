package adserve.core.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FleetBudgetLedgerTest {
    static final long BUDGET = 1_000;

    /** Eight nodes each spend until they think the budget is gone, between two syncs. */
    static long fleetSpend(boolean split) {
        BudgetLedger truth = new BudgetLedger();
        FleetBudgetLedger[] nodes = new FleetBudgetLedger[8];
        for (int k = 0; k < nodes.length; k++) nodes[k] = new FleetBudgetLedger(truth, 8, split, c -> BUDGET);
        for (FleetBudgetLedger n : nodes) n.sync(List.of("c"), 1);
        truth.charge("c", 1, 600); // spent before this window
        for (FleetBudgetLedger n : nodes) n.sync(List.of("c"), 1);
        for (FleetBudgetLedger n : nodes) {
            while (BUDGET - n.spent("c", 1) >= 10) n.charge("c", 1, 10);
        }
        return truth.spent("c", 1);
    }

    @Test
    void withoutTheSplitEveryNodeSpendsTheWholeRemainder() {
        assertThat(fleetSpend(false)).isEqualTo(600 + 8 * 400);
    }

    @Test
    void theSplitKeepsTheFleetInsideTheBudget() {
        assertThat(fleetSpend(true)).isLessThanOrEqualTo(BUDGET);
    }
}
