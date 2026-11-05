package dev.pti.etl.gtfs.adapter.out.json;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.gtfs.application.GtfsIssues;
import dev.pti.etl.gtfs.application.port.FeedReports;
import dev.pti.etl.gtfs.domain.FeedCheck;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link FeedReports} on {@code MessageJson} (DOC-21 §4, {@code report_version} 1). Building the report is a
 * serialization concern, so it lives in the outbound adapter, next to the JSON tree it writes.
 */
public final class FeedReportJson implements FeedReports {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    @Override
    public String extraColumns(Map<String, List<String>> extra) {
        JsonMapper mapper = MessageJson.mapper();
        ObjectNode node = mapper.createObjectNode();
        extra.forEach((file, columns) -> {
            ArrayNode list = node.putArray(file);
            columns.forEach(list::add);
        });
        return mapper.writeValueAsString(node);
    }

    @Override
    public String build(
            GtfsIssues issues, Map<String, Long> rowCounts, String extraColumnsJson, Map<String, Long> durations) {
        JsonMapper mapper = MessageJson.mapper();
        ObjectNode report = mapper.createObjectNode();
        report.put("report_version", 1);
        report.put("result", issues.hasErrors() ? "REJECTED" : "ACCEPTED");
        ObjectNode counts = report.putObject("row_counts");
        rowCounts.forEach(counts::put);
        report.set("errors", entries(mapper, issues, true));
        report.set("warnings", entries(mapper, issues, false));
        report.set("extra_columns", extraColumnsNode(mapper, extraColumnsJson));
        ObjectNode duration = report.putObject("duration_ms");
        durations.forEach(duration::put);
        return mapper.writeValueAsString(report);
    }

    @Override
    public String issuesToJson(GtfsIssues issues) {
        JsonMapper mapper = MessageJson.mapper();
        ObjectNode root = mapper.createObjectNode();
        for (FeedCheck check : issues.checks()) {
            ObjectNode entry = root.putObject(check.code());
            entry.put("count", issues.count(check));
            addSamples(mapper, entry.putArray("samples"), issues.samples(check));
        }
        return mapper.writeValueAsString(root);
    }

    @Override
    public GtfsIssues issuesFromJson(String json, int maxSamples) {
        GtfsIssues issues = new GtfsIssues(maxSamples);
        if (json == null || json.isBlank()) {
            return issues;
        }
        JsonMapper mapper = MessageJson.mapper();
        JsonNode root = mapper.readTree(json);
        root.properties().forEach(e -> {
            FeedCheck check = FeedCheck.valueOf(e.getKey().replace('-', '_'));
            List<Map<String, Object>> samples = new ArrayList<>();
            e.getValue().path("samples").forEach(node -> samples.add(toMap(mapper, node)));
            issues.add(check, e.getValue().path("count").asLong(), samples);
        });
        return issues;
    }

    @Override
    public Map<String, Object> readSample(String json) {
        return MessageJson.mapper().readValue(json, JSON_OBJECT);
    }

    private static ArrayNode entries(JsonMapper mapper, GtfsIssues issues, boolean errors) {
        ArrayNode array = mapper.createArrayNode();
        for (FeedCheck check : issues.checks()) {
            if (check.error() != errors) {
                continue;
            }
            ObjectNode entry = array.addObject();
            entry.put("check", check.code());
            entry.put("count", issues.count(check));
            addSamples(mapper, entry.putArray("samples"), issues.samples(check));
        }
        return array;
    }

    private static void addSamples(JsonMapper mapper, ArrayNode list, List<Map<String, Object>> samples) {
        samples.forEach(sample -> list.add(mapper.valueToTree(sample)));
    }

    private static JsonNode extraColumnsNode(JsonMapper mapper, String json) {
        return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonMapper mapper, JsonNode node) {
        return mapper.treeToValue(node, Map.class);
    }
}
