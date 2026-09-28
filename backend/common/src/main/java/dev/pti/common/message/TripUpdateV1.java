package dev.pti.common.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * TripUpdate payload, version 1 (DOC-09 §4): the stops passed since the previous update (observed) and up to ten
 * stops ahead (predicted), in increasing {@code stop_sequence}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TripUpdateV1(
        @NotNull @Size(min = 1, max = 128) String tripId,
        @NotNull @Size(min = 1, max = 64) String routeId,
        @NotNull @Min(0) @Max(1) Integer directionId,
        @NotNull @Pattern(regexp = "^[0-9]{8}$") String startDate,
        @NotNull @Size(min = 1, max = 64) String vehicleId,
        @NotNull @Size(min = 1, max = 300) List<@NotNull @Valid StopTimeUpdate> stopTimeUpdates)
        implements TripUpdate {

    public TripUpdateV1 {
        stopTimeUpdates = stopTimeUpdates == null ? null : List.copyOf(stopTimeUpdates);
    }

    /** {@code stop_sequence} strictly increases, which also rules out duplicates. JSON Schema cannot say this. */
    @JsonIgnore
    @AssertTrue(message = "stop_time_updates must be in strictly increasing stop_sequence")
    public boolean isStopSequenceIncreasing() {
        if (stopTimeUpdates == null) {
            return true;
        }
        Integer previous = null;
        for (StopTimeUpdate update : stopTimeUpdates) {
            if (update == null || update.stopSequence() == null) {
                continue;
            }
            if (previous != null && update.stopSequence() <= previous) {
                return false;
            }
            previous = update.stopSequence();
        }
        return true;
    }
}
