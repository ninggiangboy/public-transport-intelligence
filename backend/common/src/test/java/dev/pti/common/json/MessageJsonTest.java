package dev.pti.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.PayloadType;
import dev.pti.common.message.TripUpdateV1;
import dev.pti.common.message.VehiclePositionV1;
import dev.pti.common.message.VehiclePositionV2;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class MessageJsonTest {

    private static final String VP_V1 = """
            {"schema_version":1,"message_id":"0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e","entity_type":"VEHICLE_POSITION",
             "source":"gtfs-rt-simulator","event_timestamp":"2026-09-29T21:19:05.000Z",
             "produced_at":"2026-09-29T21:19:05.412Z",
             "payload":{"vehicle_id":"2050","trip_id":"1361959","route_id":"18","direction_id":0,
              "start_date":"20260929","lat":44.82312,"lon":-93.28961,"current_stop_sequence":15,
              "stop_id":"51821","current_status":"IN_TRANSIT_TO"}}
            """;

    private final tools.jackson.databind.json.JsonMapper mapper = MessageJson.mapper();

    @Test
    void writesTimestampsWithMillisecondsAndOmitsNulls() {
        VehiclePositionV1 payload = new VehiclePositionV1(
                "2050", "1361959", "18", 0, "20260929", 44.8, -93.2, null, null, 15, "51821", null);
        Envelope<VehiclePositionV1> envelope = Envelope.of(
                UUID.fromString("0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e"),
                Envelope.SIMULATOR_SOURCE,
                Instant.parse("2026-09-29T21:19:05.123456Z"),
                Instant.parse("2026-09-29T21:19:05Z"),
                payload);

        JsonNode json = mapper.valueToTree(envelope);

        assertThat(json.get("event_timestamp").asString()).isEqualTo("2026-09-29T21:19:05.123Z");
        assertThat(json.get("produced_at").asString()).isEqualTo("2026-09-29T21:19:05.000Z");
        assertThat(json.get("payload").has("bearing")).isFalse();
        assertThat(json.get("payload").has("current_status")).isFalse();
        assertThat(json.has("consistent")).isFalse();
    }

    @Test
    void bindsByEntityTypeAndSchemaVersion() {
        Envelope<?> v1 = MessageJson.toEnvelope(mapper.readTree(VP_V1));

        assertThat(v1.payload()).isInstanceOf(VehiclePositionV1.class);
        assertThat(v1.eventTimestamp()).isEqualTo(Instant.parse("2026-09-29T21:19:05Z"));

        ObjectNode v2 = (ObjectNode) mapper.readTree(VP_V1);
        v2.put("schema_version", 2);
        ((ObjectNode) v2.get("payload")).put("occupancy_status", "FULL");
        assertThat(MessageJson.toEnvelope(v2).payload()).isInstanceOfSatisfying(VehiclePositionV2.class, p -> {
            assertThat(p.occupancy()).hasToString("FULL");
        });
    }

    @Test
    void readsTheTripUpdateExample() {
        JsonNode json =
                mapper.readTree(getClass().getClassLoader().getResourceAsStream("messages/trip-update.v1.json"));

        Envelope<?> envelope = MessageJson.toEnvelope(json);

        assertThat(envelope.payload()).isInstanceOfSatisfying(TripUpdateV1.class, tu -> {
            assertThat(tu.stopTimeUpdates()).hasSize(2);
            assertThat(tu.stopTimeUpdates().get(1).departure()).isNull();
        });
        JsonNode written = mapper.valueToTree(envelope);
        assertThat(written).isEqualTo(json);
    }

    @Test
    void rejectsUnknownFields() {
        ObjectNode json = (ObjectNode) mapper.readTree(VP_V1);
        ((ObjectNode) json.get("payload")).put("occupancy_status", "FULL");

        assertThatThrownBy(() -> MessageJson.toEnvelope(json))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("occupancy_status");
    }

    @Test
    void rejectsScalarCoercionAndNumericEnums() {
        ObjectNode quotedNumber = (ObjectNode) mapper.readTree(VP_V1);
        ((ObjectNode) quotedNumber.get("payload")).put("direction_id", "0");
        assertThatThrownBy(() -> MessageJson.toEnvelope(quotedNumber)).isInstanceOf(JacksonException.class);

        ObjectNode numericEnum = (ObjectNode) mapper.readTree(VP_V1);
        ((ObjectNode) numericEnum.get("payload")).put("current_status", 1);
        assertThatThrownBy(() -> MessageJson.toEnvelope(numericEnum)).isInstanceOf(JacksonException.class);
    }

    @Test
    void rejectsTrailingTokens() {
        assertThatThrownBy(() -> mapper.readTree(VP_V1 + " {}")).isInstanceOf(JacksonException.class);
    }

    @Test
    void namesThePayloadType() {
        assertThat(MessageJson.payloadType(mapper.readTree(VP_V1))).isEqualTo(PayloadType.VEHICLE_POSITION_V1);
        assertThat(PayloadType.of(EntityType.TRIP_UPDATE, 1)).contains(PayloadType.TRIP_UPDATE_V1);
    }

    @Test
    void rejectsUnsupportedOrMissingTypes() {
        ObjectNode v3 = (ObjectNode) mapper.readTree(VP_V1);
        v3.put("schema_version", 3);
        assertThatThrownBy(() -> MessageJson.payloadType(v3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported schema_version 3 for VEHICLE_POSITION");

        ObjectNode unknown = (ObjectNode) mapper.readTree(VP_V1);
        unknown.put("entity_type", "ALERT");
        assertThatThrownBy(() -> MessageJson.payloadType(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown entity_type ALERT");

        ObjectNode missing = (ObjectNode) mapper.readTree(VP_V1);
        missing.remove("schema_version");
        assertThatThrownBy(() -> MessageJson.payloadType(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("entity_type and schema_version are required");

        ObjectNode textVersion = (ObjectNode) mapper.readTree(VP_V1);
        textVersion.put("schema_version", "1");
        assertThatThrownBy(() -> MessageJson.payloadType(textVersion)).isInstanceOf(IllegalArgumentException.class);
    }
}
