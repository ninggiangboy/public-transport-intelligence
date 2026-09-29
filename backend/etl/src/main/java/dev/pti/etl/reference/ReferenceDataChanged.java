package dev.pti.etl.reference;

/** Published when a new feed version becomes the reference data of this JVM. */
public record ReferenceDataChanged(long feedVersionId, boolean first) {}
