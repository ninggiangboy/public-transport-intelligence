package dev.pti.etl.gtfs;

import java.util.Arrays;
import java.util.List;

/** Splits a multi-statement SQL resource at the semicolons that end a line, dropping comment-only parts. */
final class FeedStatements {

    private FeedStatements() {}

    static List<String> split(String sql) {
        return Arrays.stream(sql.split(";[ \\t]*(--[^\\n]*)?(\\r?\\n|$)"))
                .map(String::strip)
                .filter(s -> s.lines()
                        .anyMatch(line -> !line.isBlank() && !line.strip().startsWith("--")))
                .toList();
    }
}
