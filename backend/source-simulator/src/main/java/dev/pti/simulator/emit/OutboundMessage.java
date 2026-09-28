package dev.pti.simulator.emit;

import dev.pti.simulator.ledger.LedgerEntry;
import java.util.Map;

/**
 * A message ready to send (DOC-25 §6.4).
 *
 * @param value the raw JSON string; scenarios may make it deliberately invalid
 * @param headers {@code schema_version} and {@code entity_type}
 */
public record OutboundMessage(String topic, String key, String value, Map<String, String> headers, LedgerEntry ledger) {

    public OutboundMessage {
        headers = Map.copyOf(headers);
    }
}
