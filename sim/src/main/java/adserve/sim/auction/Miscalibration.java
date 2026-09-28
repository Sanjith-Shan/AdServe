package adserve.sim.auction;

import adserve.core.model.Campaign;
import adserve.core.model.CreativeSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Distorted click-rate predictions for experiment 16. Each creative's smoothed click rate from the
 * logs is taken as the truth; a condition says what the auction is told instead.
 */
final class Miscalibration {
    private Miscalibration() {}

    enum Kind { BASELINE, UNIFORM, ADVERTISER, NOISE }

    /**
     * One distortion. {@code factor} scales predictions (all of them for UNIFORM, one advertiser's
     * for ADVERTISER); {@code sigma} and {@code seed} draw per-creative log-normal noise for NOISE.
     */
    record Condition(String name, Kind kind, double factor, String advertiser, double sigma, long seed) {
        static Condition baseline() {
            return new Condition("baseline", Kind.BASELINE, 1.0, null, 0, 0);
        }

        static Condition uniform(double f) {
            return new Condition("uniform_x" + f, Kind.UNIFORM, f, null, 0, 0);
        }

        static Condition advertiser(String adv, double f) {
            return new Condition(adv + "_x" + f, Kind.ADVERTISER, f, adv, 0, 0);
        }

        static Condition noise(double sigma, long seed) {
            return new Condition("noise_s" + sigma + "_seed" + seed, Kind.NOISE, 1.0, null, sigma, seed);
        }
    }

    /** True click rate per creative id, in file order. Fails if a creative id repeats. */
    static Map<String, Double> truth(List<Campaign> cs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Campaign c : cs) {
            for (CreativeSpec s : c.creatives()) {
                if (m.put(s.id(), s.clickRate()) != null) throw new IllegalArgumentException("creative id repeats: " + s.id());
            }
        }
        return m;
    }

    /**
     * Predicted click rate per creative under {@code cond}, clamped to [0, 1]. Noise is
     * mean-one log-normal, {@code exp(sigma * Z - sigma^2 / 2)}, one draw per creative in file
     * order from {@code seed}: calibrated on average, wrong creative by creative.
     */
    static Map<String, Double> predicted(List<Campaign> cs, Condition cond) {
        Map<String, Double> out = new LinkedHashMap<>();
        SplittableRandom rnd = new SplittableRandom(cond.seed());
        for (Campaign c : cs) {
            for (CreativeSpec s : c.creatives()) {
                double f = switch (cond.kind()) {
                    case BASELINE -> 1.0;
                    case UNIFORM -> cond.factor();
                    case ADVERTISER -> c.advertiserId().equals(cond.advertiser()) ? cond.factor() : 1.0;
                    case NOISE -> Math.exp(cond.sigma() * rnd.nextGaussian() - cond.sigma() * cond.sigma() / 2);
                };
                out.put(s.id(), Math.min(1.0, Math.max(0.0, s.clickRate() * f)));
            }
        }
        return out;
    }

    /** Copies of the campaigns whose creatives carry the predicted click rates; ids unchanged. */
    static List<Campaign> withPredictions(List<Campaign> cs, Map<String, Double> predicted) {
        List<Campaign> out = new ArrayList<>(cs.size());
        for (Campaign c : cs) {
            List<CreativeSpec> cr = new ArrayList<>(c.creatives().size());
            for (CreativeSpec s : c.creatives()) cr.add(new CreativeSpec(s.id(), s.durationS(), predicted.get(s.id())));
            out.add(new Campaign(c.id(), c.advertiserId(), c.name(), c.category(), c.cpcBidMicros(), c.dailyBudgetMicros(),
                    c.flightStartMs(), c.flightEndMs(), c.pacer(), c.cap(), c.targeting(), cr, c.active()));
        }
        return out;
    }

    /**
     * The per-click counterfactual: the slot's cleared price per impression is converted to a
     * cost per click with the PREDICTED rate the auction used ({@code price / predicted}) and
     * billed per TRUE expected click ({@code * truth}). Under a uniform bias both the price and
     * the predicted rate carry the same factor, so it cancels.
     */
    static double perClickBilled(long priceMicros, double predictedRate, double trueRate) {
        if (predictedRate <= 0) return 0;
        return priceMicros / predictedRate * trueRate;
    }

    /** Advertiser ids in first-seen order. */
    static List<String> advertisers(List<Campaign> cs) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (Campaign c : cs) m.put(c.advertiserId(), true);
        return new ArrayList<>(m.keySet());
    }

    static Map<String, Integer> index(List<String> xs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < xs.size(); i++) m.put(xs.get(i), i);
        return m;
    }
}
