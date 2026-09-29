package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;

/** The last commit per source on this pod, for the source health indicators (DOC-20 §6.1). */
public class SourceActivity {

    private final Map<EtlSource, Instant> lastCommit = new ConcurrentHashMap<>();
    private final Map<EtlSource, Instant> maxRecordTs = new ConcurrentHashMap<>();
    private final java.time.Clock clock;

    public SourceActivity(java.time.Clock clock) {
        this.clock = clock;
    }

    @EventListener
    public void onCommitted(MicroBatchCommitted event) {
        StreamChunkResult result = event.result();
        lastCommit.put(result.source(), clock.instant());
        if (result.minRecordTs() != null) {
            maxRecordTs.merge(result.source(), result.minRecordTs(), (a, b) -> a.isAfter(b) ? a : b);
        }
    }

    public Optional<Instant> lastCommit(EtlSource source) {
        return Optional.ofNullable(lastCommit.get(source));
    }

    public Optional<Instant> lastRecordTimestamp(EtlSource source) {
        return Optional.ofNullable(maxRecordTs.get(source));
    }
}
