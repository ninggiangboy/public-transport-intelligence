package dev.pti.simulator.scenario;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.pti.common.message.EntityType;
import java.util.Arrays;
import java.util.Locale;

/**
 * How {@code bad-data} corrupts a message, and the {@code invalid_kind} written to the ledger (DOC-25 §7.4). The
 * comment on each constant is the stage the ETL must put it in.
 */
public enum InvalidKind {
    /** {@code DESERIALIZE}. */
    MALFORMED_JSON(true, true),
    /** {@code SCHEMA}. */
    SCHEMA_VIOLATION(true, true),
    /** {@code SCHEMA}. */
    UNKNOWN_SCHEMA_VERSION(true, true),
    /** {@code QUALITY}; positions only. */
    OUT_OF_BBOX(true, false),
    /** {@code QUALITY}. */
    UNKNOWN_ROUTE(true, true),
    /** {@code QUALITY}. */
    UNKNOWN_STOP(true, true),
    /** {@code QUALITY}. */
    FUTURE_TIMESTAMP(true, true),
    /** {@code QUALITY}; trip updates only. */
    DELAY_OUT_OF_RANGE(false, true);

    private final boolean vehiclePosition;
    private final boolean tripUpdate;

    InvalidKind(boolean vehiclePosition, boolean tripUpdate) {
        this.vehiclePosition = vehiclePosition;
        this.tripUpdate = tripUpdate;
    }

    /** The ledger and API value, e.g. {@code malformed_json}. */
    @JsonValue
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static InvalidKind of(String value) {
        return Arrays.stream(values())
                .filter(k -> k.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown invalid kind " + value));
    }

    public boolean appliesTo(EntityType entityType) {
        return entityType == EntityType.VEHICLE_POSITION ? vehiclePosition : tripUpdate;
    }
}
