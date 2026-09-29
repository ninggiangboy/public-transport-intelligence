package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.PayloadHasher;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** DOC-25 §7.4 and T-14 (bad-data) on the mini feed. */
class BadDataScenarioTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void corruptsTheRequestedShareWithEveryKind() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("bad-data", "{\"ratio\": 0.2, \"duration\": \"PT10M\"}");
        kit.run(Duration.ofMinutes(10));

        List<OutboundMessage> sent = kit.sent();
        Map<InvalidKind, Integer> kinds = new EnumMap<>(InvalidKind.class);
        for (OutboundMessage m : sent) {
            if (m.ledger().invalidKind() != null) {
                kinds.merge(InvalidKind.of(m.ledger().invalidKind()), 1, Integer::sum);
                assertThat(m.ledger().scenarioRunId()).isEqualTo(run.runId());
            }
        }
        int corrupted = kinds.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(corrupted / (double) sent.size()).isBetween(0.2 * 0.8, 0.2 * 1.2);
        assertThat(kinds).containsOnlyKeys(InvalidKind.values());
    }

    /** A corrupted message replaces the original: the ledger keeps exactly the keys a clean run would have. */
    @Test
    void replacesMessagesWithoutAddingOrLosingBusinessKeys() {
        ScenarioKit plain = new ScenarioKit(Feeds.mini(), START).run(Duration.ofMinutes(5));
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.3, \"duration\": \"PT10M\"}");
        kit.run(Duration.ofMinutes(5));

        assertThat(kit.sent()).hasSameSizeAs(plain.sent());
        assertThat(keys(kit.sent())).isEqualTo(keys(plain.sent()));
    }

    @Test
    void theLedgerHashIsTheHashOfWhatIsSent() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.3, \"duration\": \"PT10M\"}");
        kit.run(Duration.ofMinutes(5));

        for (OutboundMessage m : kit.sent()) {
            if (InvalidKind.MALFORMED_JSON.value().equals(m.ledger().invalidKind())) {
                assertThat(m.ledger().payloadHash()).isNull();
                assertThat(m.value()).isNotEmpty();
            } else {
                JsonNode tree = MessageJson.mapper().readTree(m.value());
                assertThat(m.ledger().payloadHash()).isEqualTo(PayloadHasher.hash(tree));
                assertThat(m.headers().get(MessageFactory.SCHEMA_VERSION_HEADER))
                        .isEqualTo(tree.get("schema_version").asString());
                assertThat(m.ledger().schemaVersion())
                        .isEqualTo(tree.get("schema_version").asInt());
            }
        }
    }

    @Test
    void appliesOnlyKindsThatFitTheEntityType() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.5, \"kinds\": [\"out_of_bbox\", \"delay_out_of_range\"]}");
        kit.run(Duration.ofMinutes(3));

        for (OutboundMessage m : kit.sent()) {
            String kind = m.ledger().invalidKind();
            if (m.topic().equals(MessageFactory.VEHICLE_POSITIONS)) {
                assertThat(kind).isIn(null, "out_of_bbox");
            } else {
                assertThat(kind).isIn(null, "delay_out_of_range");
            }
        }
    }

    @Test
    void leavesEntityTypesThatWereNotAskedFor() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.5, \"entityTypes\": [\"VEHICLE_POSITION\"]}");
        kit.run(Duration.ofMinutes(3));

        assertThat(kit.sent())
                .filteredOn(m -> m.topic().equals(MessageFactory.TRIP_UPDATES))
                .allSatisfy(m -> assertThat(m.ledger().invalidKind()).isNull());
    }

    @Test
    void stopsCorruptingWhenTheRunEnds() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.5, \"duration\": \"PT1M\"}");
        kit.run(Duration.ofMinutes(1).plusSeconds(1));
        int end = kit.sent().size();
        kit.run(Duration.ofMinutes(2));

        assertThat(kit.sent().subList(end, kit.sent().size()))
                .allSatisfy(m -> assertThat(m.ledger().invalidKind()).isNull());
    }

    @Test
    void rejectsUnknownKindsAndRatiosOutOfRange() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("bad-data", "{\"kinds\": [\"nonsense\"]}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("kinds"));
        assertThatThrownBy(() -> kit.start("bad-data", "{\"ratio\": 0.9, \"kinds\": []}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .containsExactly("kinds", "ratio"));
    }

    private static Set<String> keys(List<OutboundMessage> sent) {
        Set<String> keys = new HashSet<>();
        sent.forEach(m -> keys.add(m.ledger().entityType() + "|" + m.ledger().businessKeys()));
        return keys;
    }
}
