package dev.pti.common.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A streaming RFC 4180 reader for GTFS files (DOC-13 §2.4): UTF-8, an optional BOM, quoted fields with doubled
 * quotes, CRLF or LF line ends. Blank lines are skipped. Column names are matched exactly.
 */
public final class GtfsCsv {

    private static final int BOM = '﻿';

    private GtfsCsv() {}

    /** Reads every data row of {@code in}; returns the header. */
    public static List<String> read(String file, InputStream in, Consumer<GtfsRecord> rows) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Parser parser = new Parser(file, reader);
            List<String> header = parser.next();
            if (header == null) {
                throw new GtfsFormatException(file + " is empty");
            }
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                columns.putIfAbsent(header.get(i).strip(), i);
            }
            Map<String, Integer> frozen = Map.copyOf(columns);
            List<String> fields;
            while ((fields = parser.next()) != null) {
                if (fields.size() == 1 && fields.getFirst().isEmpty()) {
                    continue;
                }
                rows.accept(new GtfsRecord(file, parser.line, frozen, fields.toArray(String[]::new)));
            }
            return List.copyOf(header);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Parser {

        private final String file;
        private final Reader reader;
        private final StringBuilder field = new StringBuilder();
        private long line;
        private int pushback = -2;
        private boolean first = true;

        Parser(String file, Reader reader) {
            this.file = file;
            this.reader = reader;
        }

        private int read() throws IOException {
            if (pushback != -2) {
                int c = pushback;
                pushback = -2;
                return c;
            }
            int c = reader.read();
            if (first) {
                first = false;
                if (c == BOM) {
                    c = reader.read();
                }
            }
            return c;
        }

        /** The fields of the next record, or {@code null} at the end of the input. */
        List<String> next() throws IOException {
            int c = read();
            if (c == -1) {
                return null;
            }
            line++;
            List<String> fields = new ArrayList<>();
            field.setLength(0);
            boolean quoted = false;
            boolean inQuotes = false;
            while (true) {
                if (inQuotes) {
                    if (c == -1) {
                        throw new GtfsFormatException(file + " line " + line + ": unterminated quoted field");
                    }
                    if (c == '"') {
                        int d = read();
                        if (d == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            c = d;
                            continue;
                        }
                    } else {
                        if (c == '\n') {
                            line++;
                        }
                        field.append((char) c);
                    }
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    quoted = false;
                } else if (c == '\r' || c == '\n' || c == -1) {
                    if (c == '\r') {
                        int d = read();
                        if (d != '\n') {
                            pushback = d;
                        }
                    }
                    fields.add(field.toString());
                    return fields;
                } else if (c == '"' && field.isEmpty() && !quoted) {
                    inQuotes = true;
                    quoted = true;
                } else {
                    field.append((char) c);
                }
                c = read();
            }
        }
    }
}
