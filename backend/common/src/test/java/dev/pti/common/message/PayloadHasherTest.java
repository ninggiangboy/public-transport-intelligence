package dev.pti.common.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.json.MessageJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** The mandatory hash cases of DOC-09 §9. */
class PayloadHasherTest {

    /** SHA-256 of the RFC 8785 form of the DOC-09 §3.3 example, computed independently. */
    private static final String EXAMPLE_HASH = "8b553c88d704f2c19bf9b79e1c6875e1cdb25b6df1bfcb521344cc60d0de3dc2";

    private final ObjectNode example = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);

    @Test
    void hashesTheCanonicalFormAsLowerCaseHex() {
        assertThat(PayloadHasher.hash(example)).isEqualTo(EXAMPLE_HASH);
    }

    @Test
    void dtoAndReceivedJsonHashTheSame() {
        assertThat(PayloadHasher.hash(SampleMessages.vehiclePositionEnvelope())).isEqualTo(EXAMPLE_HASH);
    }

    @Test
    void ignoresKeyOrderAndWhitespace() {
        String reordered = """
                {  "payload" : { "occupancy_status":"FEW_SEATS_AVAILABLE", "current_status":"IN_TRANSIT_TO",
                     "stop_id":"51821","current_stop_sequence":15,"speed_mps":7.8,"bearing":335,"lon":-93.28961,
                     "lat":44.82312,"start_date":"20260929","direction_id":0,"route_id":"18","trip_id":"1361959",
                     "vehicle_id":"2050" },
                   "produced_at":"2026-09-29T21:19:05.412Z", "event_timestamp":"2026-09-29T21:19:05.000Z",
                   "source":"gtfs-rt-simulator", "entity_type":"VEHICLE_POSITION",
                   "message_id":"0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e", "schema_version":2 }
                """;

        assertThat(PayloadHasher.hash(MessageJson.mapper().readTree(reordered))).isEqualTo(EXAMPLE_HASH);
    }

    @Test
    void ignoresMessageIdProducedAtAndSource() {
        ObjectNode resend = example.deepCopy();
        resend.put("message_id", "0192f4a6-7c1e-7b3a-9d2e-000000000001");
        resend.put("produced_at", "2026-09-29T21:25:00.000Z");
        resend.put("source", "replay");

        assertThat(PayloadHasher.hash(resend)).isEqualTo(EXAMPLE_HASH);
    }

    @Test
    void normalizesTheEventTimestamp() {
        ObjectNode other = example.deepCopy();
        other.put("event_timestamp", "2026-09-29T16:19:05-05:00");

        assertThat(PayloadHasher.hash(other)).isEqualTo(EXAMPLE_HASH);
    }

    @Test
    void changesWhenAnyHashedFieldChanges() {
        ObjectNode payload = example.deepCopy();
        ((ObjectNode) payload.get("payload")).put("speed_mps", 7.9);
        ObjectNode time = example.deepCopy();
        time.put("event_timestamp", "2026-09-29T21:19:05.001Z");
        ObjectNode version = example.deepCopy();
        version.put("schema_version", 1);

        assertThat(PayloadHasher.hash(payload)).isNotEqualTo(EXAMPLE_HASH);
        assertThat(PayloadHasher.hash(time)).isNotEqualTo(EXAMPLE_HASH);
        assertThat(PayloadHasher.hash(version)).isNotEqualTo(EXAMPLE_HASH);
    }

    @Test
    void requiresTheHashedFields() {
        ObjectNode missing = example.deepCopy();
        missing.remove("payload");
        ObjectNode nullTime = example.deepCopy();
        nullTime.putNull("event_timestamp");

        assertThatThrownBy(() -> PayloadHasher.hash(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("payload is required for the payload hash");
        assertThatThrownBy(() -> PayloadHasher.hash(nullTime)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cdcHashIgnoresLsnSourceTimeAndCustomerRef() {
        ObjectNode sale = (ObjectNode) MessageJson.mapper().readTree("""
                {"transaction_id":"3f2b8c1e-7d4a-4e51-9b0c-2a6f1d8e9c01","sale_point_id":"KIOSK-51631",
                 "amount":"2.50","created_at":"2026-09-29T21:19:05.000000Z","__op":"c","__deleted":"false",
                 "__lsn":100,"__source_ts_ms":1790720345000,"customer_ref":"C-1"}
                """);
        ObjectNode later = sale.deepCopy();
        later.put("__lsn", 200).put("__source_ts_ms", 1790720399000L).put("customer_ref", "C-2");
        ObjectNode changed = sale.deepCopy();
        changed.put("amount", "3.00");

        assertThat(PayloadHasher.hashCdc(later))
                .isEqualTo(PayloadHasher.hashCdc(sale))
                .hasSize(64);
        assertThat(PayloadHasher.hashCdc(changed)).isNotEqualTo(PayloadHasher.hashCdc(sale));
        assertThat(sale.has("__lsn")).as("input is not modified").isTrue();
    }
}
