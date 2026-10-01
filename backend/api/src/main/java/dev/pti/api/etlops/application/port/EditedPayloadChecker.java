package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.BusinessKeyChangedException;
import dev.pti.api.etlops.domain.InvalidPayloadException;
import dev.pti.api.etlops.domain.PiiNotAllowedException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The checks of an edited dead letter payload that need JSON and the message schemas (DOC-22 §2): it is a JSON object,
 * it passes the schema of its source, it holds no personal data and it keeps the record's entity type and business
 * key.
 */
public interface EditedPayloadChecker {

    /** The payload as it is stored, and the JSON paths whose values differ from the original. */
    record Checked(String json, List<String> changedPaths) {

        public Checked {
            changedPaths = List.copyOf(changedPaths);
        }
    }

    /**
     * @param source the {@code ops.etl_source} of the dead letter
     * @param rawPayload the original payload, which may not be JSON
     * @param edited the payload the operator sent
     * @throws InvalidPayloadException when it is not a JSON object or does not pass the schema
     * @throws PiiNotAllowedException when it has a key of the PII blocklist
     * @throws BusinessKeyChangedException when it changes the entity type or business key of a parsable original
     */
    Checked check(String source, @Nullable String rawPayload, String edited);
}
