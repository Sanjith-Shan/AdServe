package adserve.sim;

import java.util.Arrays;

/** Entry point for every offline tool: {@code ./gradlew :sim:run --args="<command> ..."}. */
public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("commands: load-ipinyou");
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0]) {
            case "load-ipinyou" -> adserve.sim.data.IpinyouLoader.main(rest);
            case "derive-bids" -> adserve.sim.data.DeriveBids.main(rest);
            case "redis-probe" -> adserve.sim.load.RedisProbe.main(rest);
            case "forecast" -> adserve.sim.pacing.Forecast.main(rest);
            case "exp2-pacing" -> adserve.sim.pacing.PacingExperiment.main(rest);
            case "exp14-pacing-pricing" -> adserve.sim.pacing.PacingExperiment.exp14(rest);
            case "exp15-bid-pacing" -> adserve.sim.pacing.PacingExperiment.exp15(rest);
            case "exp3-caps" -> adserve.sim.caps.CapExperiment.main(rest);
            case "exp11-pricing" -> adserve.sim.auction.PricingExperiment.main(rest);
            case "exp12-shading" -> adserve.sim.auction.ShadingExperiment.main(rest);
            case "burst" -> adserve.sim.load.BurstExperiment.main(rest);
            case "overload" -> adserve.sim.load.OverloadExperiment.main(rest);
            case "exp7-billing" -> adserve.sim.billing.BillingAudit.main(rest);
            case "exp8-hollow" -> adserve.sim.data.HollowExperiment.main(rest);
            case "demo" -> adserve.sim.billing.Demo.main(rest);
            case "exp4-pods" -> adserve.sim.pods.PodExperiment.main(rest);
            case "smoke" -> adserve.sim.load.Smoke.main(rest);
            case "replay-check" -> adserve.sim.load.ReplayCheck.main(rest);
            case "exp16-calibration" -> adserve.sim.auction.CalibrationExperiment.main(rest);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }
}
