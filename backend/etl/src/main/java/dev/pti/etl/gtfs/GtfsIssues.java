package dev.pti.etl.gtfs;

import dev.pti.common.json.MessageJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Counts and samples per feed check, the {@code errors}/{@code warnings} entries of the validation report
 * (DOC-21 §4). Kept as JSON text in the execution context, so that a restart does not lose the counts.
 */
public final class GtfsIssues {

    private final int maxSamples;
    private final Map<FeedCheck, Long> counts = new LinkedHashMap<>();
    private final Map<FeedCheck, List<ObjectNode>> samples = new LinkedHashMap<>();

    public GtfsIssues(int maxSamples) {
        this.maxSamples = maxSamples;
    }

    public void add(FeedCheck check, ObjectNode sample) {
        add(check, 1, List.of(sample));
    }

    public void add(FeedCheck check, long count, List<ObjectNode> newSamples) {
        if (count <= 0) {
            return;
        }
        counts.merge(check, count, Long::sum);
        List<ObjectNode> kept = samples.computeIfAbsent(check, c -> new ArrayList<>());
        for (ObjectNode s : newSamples) {
            if (kept.size() < maxSamples) {
                kept.add(s);
            }
        }
    }

    public void addAll(GtfsIssues other) {
        other.counts.forEach((check, count) -> add(check, count, other.samples.getOrDefault(check, List.of())));
    }

    public long count(FeedCheck check) {
        return counts.getOrDefault(check, 0L);
    }

    public boolean hasErrors() {
        return counts.keySet().stream().anyMatch(FeedCheck::error);
    }

    public List<FeedCheck> checks() {
        return List.copyOf(counts.keySet());
    }

    /** The {@code errors} or {@code warnings} array of the report. */
    public ArrayNode entries(boolean errors) {
        ArrayNode array = MessageJson.mapper().createArrayNode();
        counts.forEach((check, count) -> {
            if (check.error() == errors) {
                ObjectNode entry = array.addObject();
                entry.put("check", check.code());
                entry.put("count", count);
                ArrayNode list = entry.putArray("samples");
                samples.getOrDefault(check, List.of()).forEach(list::add);
            }
        });
        return array;
    }

    public String toJson() {
        ObjectNode root = MessageJson.mapper().createObjectNode();
        counts.forEach((check, count) -> {
            ObjectNode entry = root.putObject(check.code());
            entry.put("count", count);
            ArrayNode list = entry.putArray("samples");
            samples.getOrDefault(check, List.of()).forEach(list::add);
        });
        return MessageJson.mapper().writeValueAsString(root);
    }

    public static GtfsIssues fromJson(String json, int maxSamples) {
        GtfsIssues issues = new GtfsIssues(maxSamples);
        if (json == null || json.isBlank()) {
            return issues;
        }
        JsonNode root = MessageJson.mapper().readTree(json);
        root.properties().forEach(e -> {
            FeedCheck check = FeedCheck.valueOf(e.getKey().replace('-', '_'));
            List<ObjectNode> list = new ArrayList<>();
            e.getValue().path("samples").forEach(s -> list.add((ObjectNode) s));
            issues.add(check, e.getValue().path("count").asLong(), list);
        });
        return issues;
    }

    public static ObjectNode sample() {
        return MessageJson.mapper().createObjectNode();
    }
}
