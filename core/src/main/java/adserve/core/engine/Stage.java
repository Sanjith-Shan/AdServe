package adserve.core.engine;

/** The decision path, in order. Used for per-stage timing and for dropped_by in the log. */
public enum Stage {
    RESOLVE, TARGETING, POLICY, FREQUENCY_CAP, PACING, SELECTION, TOKENS, LOG;

    public String wire() {
        return name().toLowerCase();
    }
}
