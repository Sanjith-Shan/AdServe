package adserve.core.engine;

import ads.v1.AdResponse;
import ads.v1.DecisionRecord;

/** The engine's output: what the player gets, and what goes on the decision log. */
public record Decision(AdResponse response, DecisionRecord record) {}
