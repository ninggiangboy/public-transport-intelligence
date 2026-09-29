package dev.pti.common.pii;

import dev.pti.common.json.MessageJson;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Removes personal data from a payload before it is stored or logged (DOC-18 §4.1, DR-60). A JSON payload loses
 * every property named in the blocklist, at any depth; a payload that is not JSON has the quoted string value of
 * each blocked key replaced. A payload without blocked keys is returned unchanged, byte for byte.
 */
public final class PiiScrubber {

    /** {@code pti.pii.blocklist} default. */
    public static final List<String> DEFAULT_BLOCKLIST = List.of("customer_ref");

    static final String REDACTED = "[REDACTED]";

    private final Set<String> blocklist;
    private final Map<String, Pattern> patterns;

    public PiiScrubber(Collection<String> blocklist) {
        this.blocklist = Set.copyOf(blocklist);
        this.patterns = this.blocklist.stream()
                .collect(Collectors.toUnmodifiableMap(
                        key -> key, key -> Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"[^\"]*\"")));
    }

    public static PiiScrubber withDefaults() {
        return new PiiScrubber(DEFAULT_BLOCKLIST);
    }

    public Set<String> blocklist() {
        return blocklist;
    }

    public String scrub(String payload) {
        if (blocklist.stream().noneMatch(payload::contains)) {
            return payload;
        }
        JsonNode tree;
        try {
            tree = MessageJson.mapper().readTree(payload);
        } catch (JacksonException e) {
            return scrubText(payload);
        }
        if (!remove(tree)) {
            return payload;
        }
        return MessageJson.mapper().writeValueAsString(tree);
    }

    /** True when a blocked key appears anywhere in the tree. */
    public boolean containsBlocked(JsonNode tree) {
        if (tree instanceof ObjectNode object) {
            for (String name : object.propertyNames()) {
                if (blocklist.contains(name) || containsBlocked(object.get(name))) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode child : tree) {
            if (containsBlocked(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean remove(JsonNode node) {
        boolean removed = false;
        if (node instanceof ObjectNode object) {
            for (String key : blocklist) {
                removed |= object.remove(key) != null;
            }
        }
        for (JsonNode child : node) {
            removed |= remove(child);
        }
        return removed;
    }

    private String scrubText(String payload) {
        String result = payload;
        for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
            Matcher m = entry.getValue().matcher(result);
            result = m.replaceAll(Matcher.quoteReplacement("\"" + entry.getKey() + "\":\"" + REDACTED + "\""));
        }
        return result;
    }
}
