package dev.pti.common.message;

import java.util.Arrays;
import java.util.Optional;

/** Every supported (entity_type, schema_version) pair, its Java type and its JSON Schema file (DOC-09 §10). */
public enum PayloadType {
    VEHICLE_POSITION_V1(EntityType.VEHICLE_POSITION, 1, VehiclePositionV1.class, "vehicle-position.v1"),
    VEHICLE_POSITION_V2(EntityType.VEHICLE_POSITION, 2, VehiclePositionV2.class, "vehicle-position.v2"),
    TRIP_UPDATE_V1(EntityType.TRIP_UPDATE, 1, TripUpdateV1.class, "trip-update.v1");

    private final EntityType entityType;
    private final int schemaVersion;
    private final Class<? extends Payload> javaType;
    private final String schemaName;

    PayloadType(EntityType entityType, int schemaVersion, Class<? extends Payload> javaType, String schemaName) {
        this.entityType = entityType;
        this.schemaVersion = schemaVersion;
        this.javaType = javaType;
        this.schemaName = schemaName;
    }

    public static Optional<PayloadType> of(EntityType entityType, int schemaVersion) {
        return Arrays.stream(values())
                .filter(t -> t.entityType == entityType && t.schemaVersion == schemaVersion)
                .findFirst();
    }

    public static PayloadType of(Payload payload) {
        return Arrays.stream(values())
                .filter(t -> t.javaType == payload.getClass())
                .findFirst()
                .orElseThrow();
    }

    public EntityType entityType() {
        return entityType;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Class<? extends Payload> javaType() {
        return javaType;
    }

    /** Classpath resource of the payload schema. */
    public String schemaResource() {
        return "schemas/" + schemaName + ".schema.json";
    }
}
