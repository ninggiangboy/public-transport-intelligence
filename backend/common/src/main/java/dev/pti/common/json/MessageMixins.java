package dev.pti.common.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.pti.common.message.Envelope;
import dev.pti.common.message.StopTimeUpdate;
import dev.pti.common.message.TripUpdateV1;
import dev.pti.common.message.VehiclePositionV1;
import dev.pti.common.message.VehiclePositionV2;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.module.SimpleModule;

/**
 * The Jackson annotations of the message records (DOC-09), kept out of {@code dev.pti.common.message} so that package
 * stays plain Java (DR-115): snake_case names for the DTOs, and the bean-validation helpers
 * ({@code isConsistent()} and the like) are not properties.
 */
final class MessageMixins {

    private MessageMixins() {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    abstract static class SnakeCase {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    abstract static class EnvelopeNames {
        @JsonIgnore
        abstract boolean isConsistent();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    abstract static class TripUpdateNames {
        @JsonIgnore
        abstract boolean isStopSequenceIncreasing();
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    abstract static class StopTimeUpdateNames {
        @JsonIgnore
        abstract boolean isEventsConsistent();
    }

    /** Registers the mixins of every message record on a Jackson module. */
    static SimpleModule module() {
        SimpleModule module = new SimpleModule("pti-message-names");
        module.setMixInAnnotation(Envelope.class, EnvelopeNames.class);
        module.setMixInAnnotation(TripUpdateV1.class, TripUpdateNames.class);
        module.setMixInAnnotation(StopTimeUpdate.class, StopTimeUpdateNames.class);
        module.setMixInAnnotation(VehiclePositionV1.class, SnakeCase.class);
        module.setMixInAnnotation(VehiclePositionV2.class, SnakeCase.class);
        return module;
    }
}
