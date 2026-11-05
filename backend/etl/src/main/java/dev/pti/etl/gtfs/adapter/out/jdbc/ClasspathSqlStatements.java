package dev.pti.etl.gtfs.adapter.out.jdbc;

import dev.pti.common.error.FatalException;
import dev.pti.etl.gtfs.application.port.SqlStatements;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** {@link SqlStatements} on the classpath resources under {@code sql/} (DOC-14 §8). */
public final class ClasspathSqlStatements implements SqlStatements {

    @Override
    public String load(String name) {
        String path = "sql/" + name + ".sql";
        try (InputStream in = ClasspathSqlStatements.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing SQL resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FatalException("Cannot read SQL resource " + path, e);
        }
    }
}
