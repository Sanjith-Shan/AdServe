package adserve.core.model;

/** Device classes the targeting compiler understands. Bit positions are stable. */
public enum Device {
    TV, MOBILE, WEB;

    public int bit() {
        return 1 << ordinal();
    }

    /** Parses the wire value ("tv", "mobile", "web"). Unknown values return null. */
    public static Device parse(String s) {
        if (s == null) return null;
        return switch (s) {
            case "tv" -> TV;
            case "mobile" -> MOBILE;
            case "web" -> WEB;
            default -> null;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
