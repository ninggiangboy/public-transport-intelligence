package dev.pti.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.json.MessageJson;
import dev.pti.common.message.MessageSchemas;
import dev.pti.simulator.feed.Feeds;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** DOC-25 T-09: every valid message the simulator produces passes the JSON Schemas in {@code common} (DR-44). */
class SimulatorMessagesContractTest {

    private static final MessageSchemas SCHEMAS = MessageSchemas.load();

    @Test
    void everyMessagePassesItsSchema() {
        List<OutboundMessage> sent = new EmitterHarness(Feeds.mini(), Instant.parse("2026-09-29T21:30:00Z"), 42, 1.0)
                .run(Duration.ofMinutes(15))
                .sent();

        assertThat(sent).hasSizeGreaterThan(1_000);
        for (OutboundMessage m : sent) {
            JsonNode json = MessageJson.mapper().readTree(m.value());
            assertThat(SCHEMAS.validate(json)).as(m.value()).isEmpty();
        }
    }

    /** 01:10 in Chicago: the trips still running belong to the service day before. */
    @Test
    void messagesAfterMidnightPassTheirSchema() {
        List<OutboundMessage> sent = new EmitterHarness(Feeds.mini(), Instant.parse("2026-09-30T06:10:00Z"), 42, 1.0)
                .run(Duration.ofMinutes(30))
                .sent();

        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(m -> assertThat(m.value()).contains("\"start_date\":\"20260929\""));
        for (OutboundMessage m : sent) {
            assertThat(SCHEMAS.validate(MessageJson.mapper().readTree(m.value())))
                    .as(m.value())
                    .isEmpty();
        }
    }
}
