package dev.pti.etl.gtfs;

import dev.pti.common.gtfs.GtfsCsv;
import dev.pti.common.gtfs.GtfsFormatException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ObjectNode;

/**
 * The checks {@code fetch} makes from headers and {@code agency.txt} alone (DOC-21 §3.1 step 6): required files
 * (GV-01), required columns (GV-02), one valid agency time zone (GV-03), and ignored files and extra columns (GV-14).
 */
public final class FeedStructure {

    /** What the inspection found; {@code zone} is null when GV-03 failed. */
    public record Inspection(@Nullable ZoneId zone, Map<String, List<String>> extraColumns, GtfsIssues issues) {}

    private FeedStructure() {}

    public static Inspection inspect(Path dir, FeedExtractor.Extracted extracted, int maxSamples) {
        GtfsIssues issues = new GtfsIssues(maxSamples);
        Set<String> files = extracted.files();
        for (GtfsTable required : GtfsTable.REQUIRED) {
            if (!files.contains(required.file())) {
                issues.add(FeedCheck.GV_01, fileSample(required.file(), "Required file is missing"));
            }
        }
        if (!files.contains(GtfsTable.CALENDAR.file()) && !files.contains(GtfsTable.CALENDAR_DATES.file())) {
            issues.add(FeedCheck.GV_01, fileSample("calendar.txt", "Neither calendar.txt nor calendar_dates.txt"));
        }
        for (String ignored : extracted.ignored()) {
            issues.add(FeedCheck.GV_14, fileSample(ignored, null));
        }

        Map<String, List<String>> extra = new TreeMap<>();
        for (String file : files) {
            GtfsTable table = GtfsTable.byFile(file).orElseThrow();
            List<String> header;
            try {
                header = header(dir.resolve(file));
            } catch (GtfsFormatException e) {
                issues.add(FeedCheck.GV_01, fileSample(file, e.getMessage()));
                continue;
            }
            for (String column : table.requiredColumns()) {
                if (!header.contains(column)) {
                    ObjectNode sample = fileSample(file, "Required column is missing");
                    sample.put("column", column);
                    issues.add(FeedCheck.GV_02, sample);
                }
            }
            List<String> unmapped =
                    header.stream().filter(c -> !table.isMapped(c)).toList();
            if (!unmapped.isEmpty()) {
                extra.put(file, unmapped);
            }
        }

        ZoneId zone =
                files.contains(GtfsTable.AGENCY.file()) ? zone(dir.resolve(GtfsTable.AGENCY.file()), issues) : null;
        return new Inspection(zone, extra, issues);
    }

    /** The column names of a file, from its first line only. */
    static List<String> header(Path file) {
        String first;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            first = reader.readLine();
        } catch (java.nio.charset.MalformedInputException e) {
            throw new GtfsFormatException(name(file) + " is not UTF-8");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (first == null) {
            throw new GtfsFormatException(name(file) + " is empty");
        }
        InputStream in = new ByteArrayInputStream(first.getBytes(StandardCharsets.UTF_8));
        return GtfsCsv.read(name(file), in, row -> {}).stream()
                .map(String::strip)
                .toList();
    }

    private static @Nullable ZoneId zone(Path agency, GtfsIssues issues) {
        Set<String> zones = new TreeSet<>();
        try (InputStream in = Files.newInputStream(agency)) {
            GtfsCsv.read(name(agency), in, row -> {
                String tz = row.get("agency_timezone");
                if (tz != null && !tz.isBlank()) {
                    zones.add(tz.strip());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GtfsFormatException e) {
            issues.add(FeedCheck.GV_03, fileSample("agency.txt", e.getMessage()));
            return null;
        }
        if (zones.size() != 1) {
            ObjectNode sample = fileSample("agency.txt", "Expected exactly one agency_timezone");
            sample.put("timezones", String.join(", ", zones));
            issues.add(FeedCheck.GV_03, sample);
            return null;
        }
        String tz = zones.iterator().next();
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            issues.add(FeedCheck.GV_03, fileSample("agency.txt", "Not an IANA time zone: " + tz));
            return null;
        }
    }

    static String name(Path file) {
        return String.valueOf(file.getFileName());
    }

    static ObjectNode fileSample(String file, @Nullable String message) {
        ObjectNode sample = GtfsIssues.sample();
        sample.put("file", file);
        if (message != null) {
            sample.put("message", message);
        }
        return sample;
    }
}
