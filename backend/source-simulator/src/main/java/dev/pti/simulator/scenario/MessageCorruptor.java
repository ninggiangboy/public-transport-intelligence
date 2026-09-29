package dev.pti.simulator.scenario;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.EntityType;
import dev.pti.common.message.PayloadHasher;
import dev.pti.common.time.Timestamps;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.OutboundMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Corrupts one message the way DOC-25 §7.4 describes. The corrupted message replaces the original; its ledger entry
 * keeps the original business keys and carries the hash of what is actually sent ({@code null} when it is not JSON).
 */
final class MessageCorruptor {

    /** New York: a valid coordinate far outside the Twin Cities. */
    static final double FAR_LAT = 40.7128;

    static final double FAR_LON = -74.0060;

    static final Duration FUTURE_SKEW = Duration.ofHours(2);

    /** Beyond the ETL's ±2 h limit (DQ-08). */
    static final int OUT_OF_RANGE_DELAY = 9_000;

    private MessageCorruptor() {}

    /**
     * @param choice a deterministic random value that picks the variant (cut position, field, sign)
     * @param businessNow business time now, epoch milliseconds
     * @return {@code null} when the kind cannot apply to this message: a trip update whose stops are all skipped has
     *     no delay to change
     */
    static @Nullable OutboundMessage corrupt(
            OutboundMessage message, InvalidKind kind, long choice, long businessNow, UUID runId) {
        if (kind == InvalidKind.MALFORMED_JSON) {
            String value = message.value();
            int half = value.length() / 2;
            int cut = half + (int) Math.floorMod(choice, (long) (value.length() - half));
            return new OutboundMessage(
                    message.topic(),
                    message.key(),
                    value.substring(0, cut),
                    message.headers(),
                    message.ledger().corrupted(kind.value(), message.ledger().schemaVersion(), null, runId));
        }
        ObjectNode tree = (ObjectNode) MessageJson.mapper().readTree(message.value());
        ObjectNode payload = (ObjectNode) tree.get("payload");
        EntityType entityType = EntityType.valueOf(tree.get("entity_type").asString());
        Map<String, String> headers = new HashMap<>(message.headers());
        int version = message.ledger().schemaVersion();
        switch (kind) {
            case SCHEMA_VIOLATION -> schemaViolation(entityType, payload, choice);
            case UNKNOWN_SCHEMA_VERSION -> {
                version = 3;
                tree.put("schema_version", version);
                headers.put(MessageFactory.SCHEMA_VERSION_HEADER, Integer.toString(version));
            }
            case OUT_OF_BBOX -> {
                payload.put("lat", FAR_LAT);
                payload.put("lon", FAR_LON);
            }
            case UNKNOWN_ROUTE -> payload.put("route_id", "R-UNKNOWN-" + Math.floorMod(choice, 1000L));
            case UNKNOWN_STOP -> {
                String stopId = "S-UNKNOWN-" + Math.floorMod(choice, 1000L);
                if (entityType == EntityType.VEHICLE_POSITION) {
                    payload.put("stop_id", stopId);
                } else {
                    ((ObjectNode) payload.get("stop_time_updates").get(0)).put("stop_id", stopId);
                }
            }
            case FUTURE_TIMESTAMP ->
                tree.put(
                        "event_timestamp",
                        Timestamps.format(Instant.ofEpochMilli(businessNow).plus(FUTURE_SKEW)));
            case DELAY_OUT_OF_RANGE -> {
                if (!outOfRangeDelay(payload, choice)) {
                    return null;
                }
            }
            default -> throw new IllegalStateException("Not a JSON corruption: " + kind);
        }
        return new OutboundMessage(
                message.topic(),
                message.key(),
                MessageJson.mapper().writeValueAsString(tree),
                headers,
                message.ledger().corrupted(kind.value(), version, PayloadHasher.hash(tree), runId));
    }

    /**
     * One of: a required field removed, {@code direction_id} as a string, an unknown field, a latitude beyond 90
     * (positions), two stops out of order (trip updates with at least two stops).
     */
    private static void schemaViolation(EntityType entityType, ObjectNode payload, long choice) {
        List<Consumer<ObjectNode>> variants = new ArrayList<>();
        variants.add(p -> p.remove(entityType == EntityType.VEHICLE_POSITION ? "route_id" : "trip_id"));
        variants.add(p -> p.put("direction_id", p.get("direction_id").asString()));
        variants.add(p -> p.put("unexpected_field", true));
        if (entityType == EntityType.VEHICLE_POSITION) {
            variants.add(p -> p.put("lat", 123.4));
        } else if (payload.get("stop_time_updates").size() >= 2) {
            variants.add(MessageCorruptor::swapStopSequences);
        }
        variants.get((int) Math.floorMod(choice, (long) variants.size())).accept(payload);
    }

    private static void swapStopSequences(ObjectNode payload) {
        ArrayNode updates = (ArrayNode) payload.get("stop_time_updates");
        ObjectNode first = (ObjectNode) updates.get(0);
        ObjectNode second = (ObjectNode) updates.get(1);
        JsonNode sequence = first.get("stop_sequence");
        first.set("stop_sequence", second.get("stop_sequence"));
        second.set("stop_sequence", sequence);
    }

    /** Sets one {@code delay} to ±9,000 s on the first stop that has an arrival or a departure. */
    private static boolean outOfRangeDelay(ObjectNode payload, long choice) {
        int delay = (choice & 1) == 0 ? OUT_OF_RANGE_DELAY : -OUT_OF_RANGE_DELAY;
        for (JsonNode update : payload.get("stop_time_updates")) {
            ObjectNode event = event(update);
            if (event != null) {
                event.put("delay", delay);
                return true;
            }
        }
        return false;
    }

    private static @Nullable ObjectNode event(JsonNode update) {
        JsonNode arrival = update.get("arrival");
        if (arrival instanceof ObjectNode a) {
            return a;
        }
        return update.get("departure") instanceof ObjectNode d ? d : null;
    }
}
