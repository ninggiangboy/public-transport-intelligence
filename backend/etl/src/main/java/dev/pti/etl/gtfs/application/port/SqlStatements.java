package dev.pti.etl.gtfs.application.port;

/** Loads a SQL statement from {@code src/main/resources/sql} (DOC-14 §8). */
public interface SqlStatements {

    String load(String name);
}
