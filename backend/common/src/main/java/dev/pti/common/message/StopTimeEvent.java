package dev.pti.common.message;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Arrival or departure at a stop.
 *
 * @param delay seconds against the schedule; negative when early
 */
public record StopTimeEvent(@NotNull Instant time, @NotNull Integer delay) {}
