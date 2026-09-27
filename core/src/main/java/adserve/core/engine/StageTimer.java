package adserve.core.engine;

/** Receives each stage's elapsed time. The server records these into histograms. */
@FunctionalInterface
public interface StageTimer {
    void record(Stage stage, long nanos);

    StageTimer NONE = (s, n) -> {};
}
