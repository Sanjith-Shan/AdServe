package adserve.core.engine;

import ads.v1.DecisionRecord;

/**
 * Where decisions go after the response is built. Implementations must not block: the Kafka one
 * hands the record to a bounded in-memory buffer and returns, dropping (and counting) when full.
 */
@FunctionalInterface
public interface DecisionLog {
    void publish(DecisionRecord record);

    DecisionLog NONE = r -> {};
}
