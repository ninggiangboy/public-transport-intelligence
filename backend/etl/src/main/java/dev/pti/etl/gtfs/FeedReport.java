package dev.pti.etl.gtfs;

import dev.pti.common.json.MessageJson;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code gtfs_feed_version.validation_report}, format of DOC-21 §4 ({@code report_version} 1). */
public final class FeedReport {

    private FeedReport() {}

    public static String build(
            GtfsIssues issues, Map<String, Long> rowCounts, String extraColumnsJson, Map<String, Long> durations) {
        ObjectNode report = MessageJson.mapper().createObjectNode();
        report.put("report_version", 1);
        report.put("result", issues.hasErrors() ? "REJECTED" : "ACCEPTED");
        ObjectNode counts = report.putObject("row_counts");
        rowCounts.forEach(counts::put);
        report.set("errors", issues.entries(true));
        report.set("warnings", issues.entries(false));
        report.set("extra_columns", extraColumns(extraColumnsJson));
        ObjectNode duration = report.putObject("duration_ms");
        durations.forEach(duration::put);
        return MessageJson.mapper().writeValueAsString(report);
    }

    static String extraColumnsJson(Map<String, List<String>> extra) {
        ObjectNode node = MessageJson.mapper().createObjectNode();
        extra.forEach((file, columns) -> {
            ArrayNode list = node.putArray(file);
            columns.forEach(list::add);
        });
        return MessageJson.mapper().writeValueAsString(node);
    }

    private static JsonNode extraColumns(String json) {
        return json == null || json.isBlank()
                ? MessageJson.mapper().createObjectNode()
                : MessageJson.mapper().readTree(json);
    }
}
