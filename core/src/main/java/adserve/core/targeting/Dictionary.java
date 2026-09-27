package adserve.core.targeting;

import java.util.HashMap;
import java.util.Map;

/**
 * Interns strings (geos, segments, genres) to dense ints so targeting can run on bitsets. Built
 * once per campaign snapshot and read-only afterwards, so lookups need no locking.
 */
public final class Dictionary {
    private final Map<String, Integer> ids = new HashMap<>();

    /** Adds {@code s} if absent and returns its id. Only called while a snapshot is being built. */
    public int intern(String s) {
        return ids.computeIfAbsent(s, k -> ids.size());
    }

    /** Returns the id, or -1 when no campaign mentions {@code s} (it then matches no include-set). */
    public int id(String s) {
        if (s == null) return -1;
        Integer i = ids.get(s);
        return i == null ? -1 : i;
    }

    public int size() {
        return ids.size();
    }

    public int words() {
        return Math.max(1, (ids.size() + 63) >>> 6);
    }
}
