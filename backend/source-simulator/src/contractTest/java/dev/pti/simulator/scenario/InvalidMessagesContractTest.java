package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.MessageSchemas;
import dev.pti.common.message.Payload;
import dev.pti.simulator.emit.EmitterHarness;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * DOC-25 T-09 for {@code bad-data} (§7.4): each {@code invalid_kind} fails where the ETL must catch it. Malformed
 * JSON does not parse ({@code DESERIALIZE}); schema violations fail the JSON Schema or the DTO validation of DQ-01
 * ({@code SCHEMA}); every {@code QUALITY} kind passes both, so that only the business rules reject it.
 */
class InvalidMessagesContractTest {

    private static final MessageSchemas SCHEMAS = MessageSchemas.load();

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static final Instant NOW = Instant.parse("2026-09-29T21:40:00Z");

    private static final UUID RUN = UUID.fromString("0192f7b1-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    /** Enough variants of each kind: the choice picks the cut position, the field and the sign. */
    private static final int CHOICES = 12;

    @Test
    void everyKindFailsAtItsStage() {
        List<OutboundMessage> clean = sample();
        int checked = 0;
        for (InvalidKind kind : InvalidKind.values()) {
            for (OutboundMessage original : clean) {
                EntityType entityType = EntityType.valueOf(original.ledger().entityType());
                if (!kind.appliesTo(entityType)) {
                    continue;
                }
                for (long choice = 0; choice < CHOICES; choice++) {
                    OutboundMessage bad = MessageCorruptor.corrupt(original, kind, choice, NOW.toEpochMilli(), RUN);
                    if (bad == null) {
                        continue;
                    }
                    String where = kind.value() + " " + bad.value();
                    assertThat(bad.ledger().invalidKind()).isEqualTo(kind.value());
                    assertThat(bad.ledger().businessKeys())
                            .isEqualTo(original.ledger().businessKeys());
                    switch (kind) {
                        case MALFORMED_JSON ->
                            assertThatThrownBy(() -> MessageJson.mapper().readTree(bad.value()))
                                    .as(where)
                                    .isInstanceOf(JacksonException.class);
                        case SCHEMA_VIOLATION, UNKNOWN_SCHEMA_VERSION ->
                            assertThat(dq01(bad)).as(where).isNotEmpty();
                        default -> assertThat(dq01(bad)).as(where).isEmpty();
                    }
                    checked++;
                }
            }
        }
        assertThat(checked).isGreaterThan(1_000);
    }

    /** The QUALITY kinds change exactly what their rule checks (DOC-16, EXP-03 §6). */
    @Test
    void qualityKindsBreakTheirRule() {
        for (OutboundMessage original : sample()) {
            EntityType entityType = EntityType.valueOf(original.ledger().entityType());
            JsonNode before = MessageJson.mapper().readTree(original.value());
            if (entityType == EntityType.VEHICLE_POSITION) {
                JsonNode far = tree(original, InvalidKind.OUT_OF_BBOX);
                assertThat(far.at("/payload/lat").asDouble()).isEqualTo(MessageCorruptor.FAR_LAT);
                assertThat(tree(original, InvalidKind.UNKNOWN_STOP)
                                .at("/payload/stop_id")
                                .asString())
                        .startsWith("S-UNKNOWN-");
            } else {
                JsonNode delayed = tree(original, InvalidKind.DELAY_OUT_OF_RANGE);
                assertThat(delayed.toString()).contains("\"delay\":9000").doesNotContain(before.toString());
            }
            assertThat(tree(original, InvalidKind.UNKNOWN_ROUTE)
                            .at("/payload/route_id")
                            .asString())
                    .startsWith("R-UNKNOWN-");
            assertThat(tree(original, InvalidKind.FUTURE_TIMESTAMP)
                            .get("event_timestamp")
                            .asString())
                    .isEqualTo("2026-09-29T23:40:00.000Z");
        }
    }

    private static JsonNode tree(OutboundMessage original, InvalidKind kind) {
        return MessageJson.mapper()
                .readTree(MessageCorruptor.corrupt(original, kind, 0, NOW.toEpochMilli(), RUN)
                        .value());
    }

    /** What DQ-01 reports, as the ETL's {@code EnvelopeReader} checks it: schema, binding, Bean Validation. */
    private static List<String> dq01(OutboundMessage message) {
        JsonNode tree = MessageJson.mapper().readTree(message.value());
        List<String> errors = new ArrayList<>(SCHEMAS.validate(tree));
        if (!errors.isEmpty()) {
            return errors;
        }
        Envelope<? extends Payload> envelope;
        try {
            envelope = MessageJson.toEnvelope(tree);
        } catch (JacksonException | IllegalArgumentException e) {
            return List.of(e.getMessage());
        }
        VALIDATOR.validate(envelope).forEach(v -> errors.add(v.getPropertyPath() + ": " + v.getMessage()));
        return errors;
    }

    private static List<OutboundMessage> sample() {
        List<OutboundMessage> sent = new EmitterHarness(Feeds.mini(), NOW, 42, 1.0)
                .run(Duration.ofMinutes(2))
                .sent();
        return sent.subList(0, Math.min(sent.size(), 200));
    }
}
