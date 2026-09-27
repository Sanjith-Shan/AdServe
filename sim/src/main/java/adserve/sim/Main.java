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
            case "redis-probe" -> adserve.sim.load.RedisProbe.main(rest);
            case "smoke" -> adserve.sim.load.Smoke.main(rest);
            case "replay-check" -> adserve.sim.load.ReplayCheck.main(rest);
            default -> {
                System.err.println("unknown command " + args[0]);
                System.exit(2);
            }
        }
    }
}
