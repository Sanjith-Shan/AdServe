package adserve.core.pod;

/**
 * The break the pod must fill. {@code maxAds} may be lowered per request by the viewer's hourly
 * ad-load cap. A pod is either empty or satisfies every rule here.
 */
public record PodRules(int capacityS, int minAds, int maxAds, Separation separation) {
    public PodRules {
        if (capacityS < 0 || minAds < 0 || maxAds < 0) throw new IllegalArgumentException("negative rule");
    }

    public static PodRules standard(int capacityS) {
        return new PodRules(capacityS, 1, 6, Separation.ADJACENT);
    }

    public PodRules withMaxAds(int max) {
        return new PodRules(capacityS, minAds, Math.min(maxAds, max), separation);
    }
}
