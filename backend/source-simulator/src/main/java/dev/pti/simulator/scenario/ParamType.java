package dev.pti.simulator.scenario;

/** The type of a parameter in the scenario catalog (DOC-25 §8), for the UI form. */
public enum ParamType {
    /** Inferred from the Java type of the record component. */
    AUTO,
    STRING,
    INT,
    DOUBLE,
    BOOLEAN,
    DURATION,
    ENUM,
    ENUM_LIST,
    DOUBLE_LIST,
    /** A {@code route_id} of the feed. */
    ROUTE
}
