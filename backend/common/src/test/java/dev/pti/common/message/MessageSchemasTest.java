package dev.pti.common.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.pti.common.json.MessageJson;
import java.io.InputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class MessageSchemasTest {

    private static final MessageSchemas SCHEMAS = MessageSchemas.load();

    static Stream<String> schemaResources() {
        return Stream.concat(
                Stream.of(MessageSchemas.ENVELOPE_RESOURCE),
                Stream.of(PayloadType.values()).map(PayloadType::schemaResource));
    }

    @ParameterizedTest
    @MethodSource("schemaResources")
    void everySchemaIsValidDraft202012(String resource) throws Exception {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema meta = registry.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));
        JsonNode schema;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            schema = MessageJson.mapper().readTree(in);
        }

        assertThat(meta.validate(schema)).isEmpty();
        assertThat(schema.get("$schema").asString()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void acceptsTheDocumentedExamples() {
        assertThat(SCHEMAS.validate(SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2)))
                .isEmpty();
        assertThat(SCHEMAS.validate(SampleMessages.json(SampleMessages.TRIP_UPDATE_V1)))
                .isEmpty();
    }

    /** P1-07 acceptance: what the DTOs serialize to matches the schemas. */
    @Test
    void serializedDtosMatchTheirSchemas() {
        Envelope<VehiclePositionV1> v1 = Envelope.of(
                SampleMessages.vehiclePositionEnvelope().messageId(),
                Envelope.SIMULATOR_SOURCE,
                SampleMessages.vehiclePositionEnvelope().eventTimestamp(),
                SampleMessages.vehiclePositionEnvelope().producedAt(),
                SampleMessages.vehiclePositionV1());

        for (Envelope<?> envelope :
                new Envelope<?>[] {v1, SampleMessages.vehiclePositionEnvelope(), SampleMessages.tripUpdateEnvelope()}) {
            JsonNode json = MessageJson.mapper().valueToTree(envelope);
            assertThat(SCHEMAS.validate(json))
                    .as(envelope.entityType() + " v" + envelope.schemaVersion())
                    .isEmpty();
        }
    }

    @Test
    void rejectsUnknownFieldsInEnvelopeAndPayload() {
        ObjectNode envelope = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        envelope.put("trace_id", "x");
        ObjectNode payload = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        ((ObjectNode) payload.get("payload")).put("odometer", 1);

        assertThat(SCHEMAS.validate(envelope)).singleElement().asString().contains("trace_id");
        assertThat(SCHEMAS.validate(payload)).singleElement().asString().contains("odometer");
    }

    @Test
    void rejectsV2FieldsInV1() {
        ObjectNode message = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        message.put("schema_version", 1);

        assertThat(SCHEMAS.validate(message)).singleElement().asString().contains("occupancy_status");
    }

    @Test
    void requiresMillisecondUtcEventTimestampsAndUuidV7() {
        ObjectNode seconds = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        seconds.put("event_timestamp", "2026-09-29T21:19:05Z");
        ObjectNode offset = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        offset.put("event_timestamp", "2026-09-29T16:19:05.000-05:00");
        ObjectNode uuidV4 = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        uuidV4.put("message_id", "3f2b8c1e-7d4a-4e51-9b0c-2a6f1d8e9c01");

        assertThat(SCHEMAS.validate(seconds)).hasSize(1);
        assertThat(SCHEMAS.validate(offset)).hasSize(1);
        assertThat(SCHEMAS.validate(uuidV4)).hasSize(1);
    }

    @Test
    void reportsUnsupportedVersionsAfterTheEnvelopeChecks() {
        ObjectNode message = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        message.put("schema_version", 2);

        assertThat(SCHEMAS.validate(message)).containsExactly("Unsupported schema_version 2 for TRIP_UPDATE");
    }

    @Test
    void appliesTheScheduleRelationshipRules() {
        ObjectNode scheduledWithoutEvents = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        ObjectNode first = firstUpdate(scheduledWithoutEvents);
        first.remove("arrival");
        first.remove("departure");

        ObjectNode skippedWithArrival = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        firstUpdate(skippedWithArrival).put("schedule_relationship", "SKIPPED");

        ObjectNode skippedWithoutEvents = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        ObjectNode skipped = firstUpdate(skippedWithoutEvents);
        skipped.remove("arrival");
        skipped.remove("departure");
        skipped.put("schedule_relationship", "NO_DATA");

        assertThat(SCHEMAS.validate(scheduledWithoutEvents)).isNotEmpty();
        assertThat(SCHEMAS.validate(skippedWithArrival)).isNotEmpty();
        assertThat(SCHEMAS.validate(skippedWithoutEvents)).isEmpty();
    }

    @Test
    void limitsStopTimeUpdatesToOneThroughThreeHundred() {
        ObjectNode empty = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        ((ObjectNode) empty.get("payload")).putArray("stop_time_updates");

        ObjectNode tooMany = SampleMessages.json(SampleMessages.TRIP_UPDATE_V1);
        ArrayNode updates = (ArrayNode) tooMany.get("payload").get("stop_time_updates");
        JsonNode template = updates.get(1);
        for (int i = 0; i < 299; i++) {
            updates.add(((ObjectNode) template.deepCopy()).put("stop_sequence", 16 + i));
        }

        assertThat(SCHEMAS.validate(empty)).hasSize(1);
        assertThat(SCHEMAS.validate(tooMany)).hasSize(1);
    }

    @Test
    void stopsAtEnvelopeErrors() {
        ObjectNode message = SampleMessages.json(SampleMessages.VEHICLE_POSITION_V2);
        message.remove("payload");
        message.put("schema_version", 0);

        assertThat(SCHEMAS.validate(message)).hasSize(2);
    }

    private static ObjectNode firstUpdate(ObjectNode message) {
        return (ObjectNode) message.get("payload").get("stop_time_updates").get(0);
    }
}
