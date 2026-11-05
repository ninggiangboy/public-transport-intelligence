package dev.pti.etl.core.application.port;

import dev.pti.etl.core.domain.InboundMessage;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Extracts the business key of a message that failed, as far as it parses (DOC-22 §1.2). */
public interface BestEffortKeys {

    /** A read-only view of the parsed value, so callers do not see JSON. */
    interface Fields {

        Fields path(String name);

        @Nullable
        String asString();
    }

    @Nullable
    String of(InboundMessage message, Function<Fields, @Nullable String> extractor);
}
