package dev.pti.etl.flags;

/** A runtime flag was read for the first time or changed (DR-19). */
public record RuntimeFlagChanged(String key, boolean enabled) {}
