package adserve.core.pod;

/** Competitive separation between creatives of the same category. */
public enum Separation {
    /** No rule. */
    NONE,
    /** Two creatives of one category may share a pod but never play back to back. */
    ADJACENT,
    /** At most one creative per category in the whole pod. */
    POD
}
