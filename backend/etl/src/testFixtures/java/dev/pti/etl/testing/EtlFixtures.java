package dev.pti.etl.testing;

import dev.pti.common.json.MessageJson;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.reference.BoundingBox;
import dev.pti.etl.reference.ReferenceData;
import dev.pti.etl.reference.ServiceCalendar;
import dev.pti.etl.reference.StopTimes;
import dev.pti.etl.reference.TripRef;
import dev.pti.etl.rules.RuleContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.node.ObjectNode;

/**
 * Route 18 of the mini feed (DOC-44) as {@link ReferenceData}, and the DOC-09 example messages as mutable JSON. The
 * business clock of the DQ tests is {@link #NOW} (DOC-16 §8).
 */
public final class EtlFixtures {

    public static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");
    public static final ZoneId AGENCY_ZONE = ZoneId.of("America/Chicago");
    public static final String TRIP = "1361959";

    private static final AtomicLong OFFSETS = new AtomicLong();

    private EtlFixtures() {}

    /**
     * The ACTIVE feed: routes 18 and 901, the stops the examples use, trip 1361959 (route 18, direction 0, service
     * 1 on weekdays) scheduled at stop 14 at 16:16:00 and stop 15 at 16:17:00, Chicago time.
     */
    public static ReferenceData referenceData() {
        boolean[] weekdays = {true, true, true, true, true, false, false};
        ServiceCalendar calendar = new ServiceCalendar(
                List.of(new ServiceCalendar.Weekly(
                        "1", weekdays, LocalDate.parse("2026-09-28"), LocalDate.parse("2026-11-13"))),
                List.of());
        StopTimes stopTimes = new StopTimes(
                date -> Map.of(TRIP, new int[][] {{14, 15}, {16 * 3600 + 16 * 60, 16 * 3600 + 17 * 60}}), 4);
        return new ReferenceData(
                3,
                AGENCY_ZONE,
                new BoundingBox(-93.40, 44.80, -93.10, 45.00),
                Set.of("18", "901"),
                Set.of("51631", "51821", "1417", "1418"),
                Map.of(TRIP, new TripRef("18", (short) 0, "1")),
                calendar,
                stopTimes);
    }

    /** {@code pti.dq.*} defaults (DOC-16 §6). */
    public static DqProperties dq() {
        return dq(Map.of());
    }

    public static DqProperties dq(Map<String, DqProperties.Rule> rules) {
        return new DqProperties(
                Duration.ofHours(1),
                Duration.ofHours(2),
                0.1,
                new BigDecimal("500.00"),
                Duration.ofMinutes(5),
                new DqProperties.PostWrite(true, Duration.ofSeconds(30)),
                rules);
    }

    public static RuleContext context() {
        return new RuleContext(NOW, Duration.ZERO, false, referenceData());
    }

    public static RuleContext replayContext() {
        return new RuleContext(NOW, Duration.ZERO, true, referenceData());
    }

    /** DOC-09 §3 vehicle position, schema version 2, event 21:19:05Z. */
    public static ObjectNode vehiclePosition() {
        return object("""
                {"schema_version":2,"message_id":"0192f4a6-7c1e-7b3a-9d2e-5f0a1b2c3d4e","entity_type":"VEHICLE_POSITION",
                 "source":"gtfs-rt-simulator","event_timestamp":"2026-09-29T21:19:05.000Z",
                 "produced_at":"2026-09-29T21:19:05.412Z",
                 "payload":{"vehicle_id":"2050","trip_id":"1361959","route_id":"18","direction_id":0,
                  "start_date":"20260929","lat":44.82312,"lon":-93.28961,"bearing":335.0,"speed_mps":7.8,
                  "current_stop_sequence":15,"stop_id":"51821","current_status":"IN_TRANSIT_TO",
                  "occupancy_status":"FEW_SEATS_AVAILABLE"}}
                """);
    }

    /** DOC-09 §4 trip update, schema version 1, event 21:19:30Z, stops 14 and 15. */
    public static ObjectNode tripUpdate() {
        return object("""
                {"schema_version":1,"message_id":"0192f4a6-8a10-7d44-b0c1-22aa4c1e9f10","entity_type":"TRIP_UPDATE",
                 "source":"gtfs-rt-simulator","event_timestamp":"2026-09-29T21:19:30.000Z",
                 "produced_at":"2026-09-29T21:19:30.107Z",
                 "payload":{"trip_id":"1361959","route_id":"18","direction_id":0,"start_date":"20260929",
                  "vehicle_id":"2050","stop_time_updates":[
                   {"stop_sequence":14,"stop_id":"51631",
                    "arrival":{"time":"2026-09-29T21:19:12.000Z","delay":192},
                    "departure":{"time":"2026-09-29T21:19:25.000Z","delay":205},
                    "schedule_relationship":"SCHEDULED"},
                   {"stop_sequence":15,"stop_id":"51821",
                    "arrival":{"time":"2026-09-29T21:20:20.000Z","delay":200},
                    "schedule_relationship":"SCHEDULED"}]}}
                """);
    }

    /** A Debezium change of {@code ticket_transaction} after unwrap (DOC-09 §5), created one minute before now. */
    public static ObjectNode ticketSale() {
        return object("""
                {"transaction_id":"01a0e9de-0000-7000-8000-000000000001","sale_point_id":"KIOSK-053",
                 "route_id":null,"stop_id":"51631","ticket_type":"DAY","txn_type":"SALE","amount":"5.00",
                 "currency":"USD","refund_of":null,"customer_ref":"cust-2ce2921a","status":"COMPLETED",
                 "created_at":"2026-09-29T21:19:00.000000Z","updated_at":"2026-09-29T21:19:00.000000Z",
                 "__deleted":"false","__op":"c","__lsn":32093984,"__source_ts_ms":1790630340100}
                """);
    }

    public static ObjectNode salePoint() {
        return object("""
                {"sale_point_id":"KIOSK-053","name":"Nicollet & Lake","kind":"KIOSK","stop_id":"51631",
                 "route_id":"18","created_at":"2026-09-01T12:00:00.000000Z","updated_at":"2026-09-01T12:00:00.000000Z",
                 "__deleted":"false","__op":"r","__lsn":1200,"__source_ts_ms":1788264000000}
                """);
    }

    public static InboundMessage message(EtlSource source, ObjectNode value) {
        return message(source, MessageJson.mapper().writeValueAsString(value));
    }

    public static InboundMessage message(EtlSource source, String value) {
        return message(source, value.getBytes(StandardCharsets.UTF_8));
    }

    public static InboundMessage message(EtlSource source, byte[] value) {
        return new InboundMessage(
                source, "key", value, source.requireTopic(), 0, OFFSETS.incrementAndGet(), NOW, Map.of());
    }

    public static InboundMessage tombstone(EtlSource source) {
        return new InboundMessage(
                source, "key", null, source.requireTopic(), 0, OFFSETS.incrementAndGet(), NOW, Map.of());
    }

    private static ObjectNode object(String json) {
        return (ObjectNode) MessageJson.mapper().readTree(json);
    }
}
