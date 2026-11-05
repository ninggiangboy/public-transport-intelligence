package dev.pti.simulator.control.adapter.in.web;

import org.jspecify.annotations.Nullable;

/** The body of {@code PUT /sim/rate}: one or both multipliers (DOC-25 §8). */
public record RateRequest(@Nullable Double gtfsRt, @Nullable Double ticketing) {}
