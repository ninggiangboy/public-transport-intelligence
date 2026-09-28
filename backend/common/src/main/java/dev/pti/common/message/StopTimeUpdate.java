package dev.pti.common.message;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** One stop of a TripUpdate (DOC-09 §4). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StopTimeUpdate(
        @NotNull @Min(0) Integer stopSequence,
        @NotNull @Size(min = 1, max = 64) String stopId,
        @Nullable @Valid StopTimeEvent arrival,
        @Nullable @Valid StopTimeEvent departure,
        @NotNull ScheduleRelationship scheduleRelationship) {

    /** SCHEDULED needs an arrival or a departure; SKIPPED and NO_DATA carry neither. */
    @JsonIgnore
    @AssertTrue(message = "SCHEDULED needs arrival or departure; SKIPPED and NO_DATA must have neither")
    public boolean isEventsConsistent() {
        if (scheduleRelationship == null) {
            return true;
        }
        boolean hasEvent = arrival != null || departure != null;
        return scheduleRelationship == ScheduleRelationship.SCHEDULED ? hasEvent : !hasEvent;
    }
}
