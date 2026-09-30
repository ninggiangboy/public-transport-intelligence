package dev.pti.common.events;

/**
 * The minimum visibility of a UI event or alert (DOC-33 §4, ADR-0023): {@code PUBLIC} reaches everyone,
 * {@code OPERATIONS} and {@code ENGINEERING} only signed-in users. Serialized as the constant name.
 */
public enum Audience {
    PUBLIC,
    OPERATIONS,
    ENGINEERING
}
