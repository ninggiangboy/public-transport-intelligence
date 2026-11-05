package dev.pti.etl.reference.application.port;

/**
 * Reloads the reference data of this JVM from the ACTIVE feed version (DOC-21 §6.2). Called on schedule and right
 * after a GTFS feed becomes ACTIVE, so that the reader sees the new feed without waiting for the next tick.
 */
public interface ReferenceRefresher {

    /** Reloads when the ACTIVE version changed; never throws. */
    void refresh();
}
