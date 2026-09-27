package adserve.core.pod;

import java.util.List;

/** Items in play order, and their total value. */
public record Pod(List<Item> items, long value) {
    public static final Pod EMPTY = new Pod(List.of(), 0);

    public static Pod of(List<Item> ordered) {
        long v = 0;
        for (Item i : ordered) v += i.value();
        return new Pod(List.copyOf(ordered), v);
    }

    public int durationS() {
        int d = 0;
        for (Item i : items) d += i.durationS();
        return d;
    }

    public int size() {
        return items.size();
    }
}
