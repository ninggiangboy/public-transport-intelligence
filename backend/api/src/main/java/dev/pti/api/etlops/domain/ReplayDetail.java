package dev.pti.api.etlops.domain;

import org.jspecify.annotations.Nullable;

/** A replay with the progress of its running step, when it has one (DOC-32 E-52). */
public record ReplayDetail(ReplayRequest request, @Nullable ReplayProgress progress) {}
