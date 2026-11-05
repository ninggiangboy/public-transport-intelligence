package dev.pti.etl.write.application;

import dev.pti.etl.write.application.port.WriteMetrics;

/**
 * The name {@link WriteMetrics} had while it lived in {@code write.application}, kept because
 * {@code stream.application.StreamChunkTemplate} still depends on it: that class belongs to another slice of Phase R
 * and its architecture violation is frozen, so renaming the type here would show up as a new violation. Removed when
 * the stream slice is refactored (RF-03).
 */
public interface WriteStats extends WriteMetrics {}
