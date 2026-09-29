package dev.pti.etl.write;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads a statement from {@code src/main/resources/sql}, where the canonical SQL of DOC-14 §8 lives. */
public final class SqlResource {

    private SqlResource() {}

    public static String load(String name) {
        String path = "sql/" + name + ".sql";
        try (InputStream in = SqlResource.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing SQL resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read SQL resource " + path, e);
        }
    }
}
