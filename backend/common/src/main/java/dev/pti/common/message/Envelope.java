package dev.pti.common.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One GTFS-realtime entity on Kafka (DOC-09 §2, DR-03, DR-04). A resend gets a new {@code messageId}.
 *
 * @param schemaVersion version of the payload for this entity type
 * @param eventTimestamp when the data was observed (event time)
 * @param producedAt when the message was published
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Envelope<P extends Payload>(
        @NotNull @Min(1) Integer schemaVersion,
        @NotNull UUID messageId,
        @NotNull EntityType entityType,
        @NotNull @Pattern(regexp = "^[a-z][a-z0-9-]{0,63}$") String source,
        @NotNull Instant eventTimestamp,
        @NotNull Instant producedAt,
        @NotNull @Valid P payload) {

    /** The {@code source} written by the simulator. */
    public static final String SIMULATOR_SOURCE = "gtfs-rt-simulator";

    /** Builds an envelope whose type and version follow from the payload. */
    public static <P extends Payload> Envelope<P> of(
            UUID messageId, String source, Instant eventTimestamp, Instant producedAt, P payload) {
        PayloadType type = PayloadType.of(payload);
        return new Envelope<>(
                type.schemaVersion(), messageId, type.entityType(), source, eventTimestamp, producedAt, payload);
    }

    /** The envelope's entity type and version must name the payload's own type. */
    @JsonIgnore
    @AssertTrue(message = "entity_type and schema_version do not match the payload")
    public boolean isConsistent() {
        return payload == null
                || entityType == null
                || schemaVersion == null
                || PayloadType.of(entityType, schemaVersion)
                        .map(t -> t.javaType() == payload.getClass())
                        .orElse(false);
    }
}
