package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.json.MessageJson;
import dev.pti.simulator.emit.OutboundMessage;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.ledger.LedgerEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/** DOC-25 §7.5 and T-14 (duplicates) on the mini feed. */
class DuplicatesScenarioTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void resendsHaveTheSameKeysAndHashAsTheOriginal() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        ScenarioRun run = kit.start("duplicates", "{\"ratio\": 0.2, \"duration\": \"PT5M\"}");
        kit.run(Duration.ofMinutes(7));

        Map<UUID, OutboundMessage> byId = new HashMap<>();
        kit.sent().forEach(m -> byId.put(m.ledger().messageId(), m));
        List<OutboundMessage> resends =
                kit.sent().stream().filter(m -> m.ledger().resendOf() != null).toList();

        Instant end = START.plus(Duration.ofMinutes(5));
        long originals = kit.sent().stream()
                .filter(m ->
                        m.ledger().resendOf() == null && m.ledger().producedAt().isBefore(end))
                .count();
        assertThat(resends.size() / (double) originals).isBetween(0.2 * 0.8, 0.2 * 1.2);
        for (OutboundMessage resend : resends) {
            LedgerEntry r = resend.ledger();
            OutboundMessage original = byId.get(r.resendOf());
            assertThat(original).as("original of %s", r.messageId()).isNotNull();
            LedgerEntry o = original.ledger();
            assertThat(r.messageId()).isNotEqualTo(o.messageId());
            assertThat(r.businessKeys()).isEqualTo(o.businessKeys());
            assertThat(r.payloadHash()).isEqualTo(o.payloadHash());
            assertThat(r.scenarioRunId()).isEqualTo(run.runId());
            assertThat(o.scenarioRunId()).isNull();
            assertThat(Duration.between(o.producedAt(), r.producedAt()))
                    .isBetween(Duration.ZERO, Duration.ofSeconds(61));
            assertThat(withoutIdAndSendTime(resend)).isEqualTo(withoutIdAndSendTime(original));
            assertThat(resend.key()).isEqualTo(original.key());
            assertThat(resend.headers()).isEqualTo(original.headers());
        }
        assertThat(kit.resends.depth()).isZero();
    }

    @Test
    void keepsSendingQueuedResendsAfterTheRunEnds() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start(
                "duplicates",
                "{\"ratio\": 0.5, \"minDelay\": \"PT2M\", \"maxDelay\": \"PT2M\", \"duration\": \"PT1M\"}");
        kit.run(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(kit.resends.depth()).isPositive();
        long before =
                kit.sent().stream().filter(m -> m.ledger().resendOf() != null).count();
        assertThat(before).isZero();

        kit.run(Duration.ofMinutes(3));

        assertThat(kit.resends.depth()).isZero();
        assertThat(kit.sent()).anySatisfy(m -> assertThat(m.ledger().resendOf()).isNotNull());
    }

    /** DOC-25 §7.1: duplicates runs before bad-data, so a resend is always the clean message. */
    @Test
    void resendsAreCleanWhenBadDataRunsToo() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
        kit.start("bad-data", "{\"ratio\": 0.5, \"duration\": \"PT5M\"}");
        kit.start("duplicates", "{\"ratio\": 0.5, \"duration\": \"PT5M\"}");
        kit.run(Duration.ofMinutes(7));

        Map<UUID, OutboundMessage> byId = new HashMap<>();
        kit.sent().forEach(m -> byId.put(m.ledger().messageId(), m));
        List<OutboundMessage> resends =
                kit.sent().stream().filter(m -> m.ledger().resendOf() != null).toList();
        assertThat(resends)
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m.ledger().invalidKind()).isNull());
        assertThat(resends)
                .anySatisfy(
                        m -> assertThat(byId.get(m.ledger().resendOf()).ledger().invalidKind())
                                .isNotNull());
    }

    @Test
    void rejectsAMaximumDelayBelowTheMinimum() {
        ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);

        assertThatThrownBy(() -> kit.start("duplicates", "{\"minDelay\": \"PT2M\", \"maxDelay\": \"PT1M\"}"))
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .containsExactly(new ScenarioException.FieldError(
                                        "maxDelay", "must not be less than minDelay")));
    }

    private static ObjectNode withoutIdAndSendTime(OutboundMessage m) {
        ObjectNode tree = (ObjectNode) MessageJson.mapper().readTree(m.value());
        tree.remove("message_id");
        tree.remove("produced_at");
        return tree;
    }
}
