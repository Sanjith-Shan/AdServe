package adserve.core.engine;

import adserve.core.caps.CapStore;
import adserve.core.pacing.PacingPlan;
import adserve.core.pod.GreedySolver;
import adserve.core.policy.BrandSafety;
import adserve.core.token.TokenCodec;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class Engines {
    private Engines() {}

    static DecisionEngine engine(CampaignSnapshot snap, CapStore caps, EngineConfig cfg, DecisionLog log) {
        BudgetLedger budget = new BudgetLedger();
        PacingController pacing = PacingController.standard(cfg.pacingSlotMs(), c -> PacingPlan.flat(1440), budget);
        return new DecisionEngine(cfg, SnapshotSource.fixed(snap), caps, budget, pacing, new GreedySolver(true),
                new TokenCodec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)), log,
                BrandSafety.defaults(), v -> List.of(), () -> 0.0, StageTimer.NONE);
    }
}
