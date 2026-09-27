package adserve.core.targeting;

/** Small helpers over long[] bitsets. All methods are allocation-free except {@link #of}. */
public final class Bits {
    private Bits() {}

    public static void set(long[] bits, int i) {
        bits[i >>> 6] |= 1L << (i & 63);
    }

    public static boolean get(long[] bits, int i) {
        return i >= 0 && (i >>> 6) < bits.length && (bits[i >>> 6] & (1L << (i & 63))) != 0;
    }

    public static boolean intersects(long[] a, long[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            if ((a[i] & b[i]) != 0) return true;
        }
        return false;
    }

    public static boolean isEmpty(long[] a) {
        for (long w : a) {
            if (w != 0) return false;
        }
        return true;
    }

    public static long[] of(int words) {
        return new long[words];
    }
}
