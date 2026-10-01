package dev.pti.api.etlops.domain;

/** A parameter of a job execution (DOC-32 E-32). */
public record JobParameter(String name, String type, String value, boolean identifying) {}
