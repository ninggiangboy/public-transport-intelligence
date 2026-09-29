package dev.pti.etl.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes a message to the processor of its source (DOC-19 §4.2), for replay jobs that read several sources. */
public final class MessageProcessors {

    private final Map<EtlSource, MessageProcessor> bySource = new EnumMap<>(EtlSource.class);

    public MessageProcessors(List<MessageProcessor> processors) {
        for (MessageProcessor p : processors) {
            if (bySource.put(p.source(), p) != null) {
                throw new IllegalStateException("Two processors for " + p.source());
            }
        }
    }

    public MessageProcessor forSource(EtlSource source) {
        MessageProcessor processor = bySource.get(source);
        if (processor == null) {
            throw new IllegalArgumentException("No processor for " + source);
        }
        return processor;
    }
}
