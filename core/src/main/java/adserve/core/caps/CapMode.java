package adserve.core.caps;

/** What to do when the counter store cannot answer inside its deadline. */
public enum CapMode {
    /** Treat counts as zero and serve. Protects revenue, risks cap violations. */
    UNKNOWN_ALLOW,
    /** Drop every capped campaign for this request. Protects viewers, costs revenue. */
    UNKNOWN_DENY;

    public String wire() {
        return name().toLowerCase();
    }
}
