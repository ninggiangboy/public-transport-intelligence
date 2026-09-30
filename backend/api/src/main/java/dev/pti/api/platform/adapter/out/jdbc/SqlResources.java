package dev.pti.api.platform.adapter.out.jdbc;

import dev.pti.common.error.FatalException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the SQL of a repository from {@code src/main/resources/sql/<group>/<name>.sql} (DOC-31 §10.1). A repository
 * keeps the text in a constant, so each file is read once, when the class loads, and a missing file stops the
 * application at start instead of failing a request.
 */
public final class SqlResources {

    private static final String ROOT = "sql/";

    private SqlResources() {}

    /** @param name {@code <group>/<name>}, for example {@code platform/active-feed} */
    public static String read(String name) {
        String path = ROOT + name + ".sql";
        try (InputStream in = SqlResources.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new FatalException("Missing SQL resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FatalException("Cannot read SQL resource " + path, e);
        }
    }
}
