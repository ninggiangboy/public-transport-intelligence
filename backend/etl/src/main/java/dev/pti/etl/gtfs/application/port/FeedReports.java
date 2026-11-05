package dev.pti.etl.gtfs.application.port;

import dev.pti.etl.gtfs.application.GtfsIssues;
import java.util.List;
import java.util.Map;

/**
 * Serializes the GTFS issues and the validation report of DOC-21 §4 to and from JSON, so that the issue counts of a
 * running job survive a restart in the execution context. JSON lives in the adapter, not in the application.
 */
public interface FeedReports {

    /** {@code extra_columns} of the report, as JSON text: file -> list of column names (GV-14). */
    String extraColumns(Map<String, List<String>> extra);

    /** The whole {@code validation_report} of DOC-21 §4 ({@code report_version} 1), as JSON. */
    String build(GtfsIssues issues, Map<String, Long> rowCounts, String extraColumnsJson, Map<String, Long> durations);

    /** The issue counts and kept samples, as JSON, for the execution context. */
    String issuesToJson(GtfsIssues issues);

    /** The issues a previous step stored, or empty ones when {@code json} is blank. */
    GtfsIssues issuesFromJson(String json, int maxSamples);

    /** One sample read from a SQL check (a {@code jsonb} object), as a plain map. */
    Map<String, Object> readSample(String json);
}
