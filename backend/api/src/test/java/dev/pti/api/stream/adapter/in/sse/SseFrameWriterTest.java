package dev.pti.api.stream.adapter.in.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.stream.domain.Frame;
import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.stream.domain.ResyncReason;
import dev.pti.api.testing.StreamFixtures;
import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/** The frames of DOC-33 §2.2, §5.9, §5.10 as they go on the wire. */
class SseFrameWriterTest {

    private static final Instant AT = Instant.parse("2026-09-29T21:19:31.020Z");

    private final SseFrameWriter writer =
            new SseFrameWriter(JsonMapper.builder().build());

    private static String wire(SseEmitter.SseEventBuilder builder) {
        Set<SseEmitter.DataWithMediaType> parts = builder.build();
        return parts.stream().map(part -> String.valueOf(part.getData())).collect(Collectors.joining());
    }

    @Test
    @DisplayName("An event frame has id, event and one line of JSON with the envelope and the data")
    void eventFrame() {
        HubEvent event = StreamFixtures.alert(AT, Audience.PUBLIC, "18");

        String wire = wire(writer.build(new Frame.Event(event, false, true)));

        assertThat(wire).startsWith("id:" + event.id() + "\nevent:alert.created\ndata:");
        String json = wire.substring(wire.indexOf("data:") + 5).strip();
        assertThat(json).doesNotContain("\n").contains("\"channel\":\"alerts\"").contains("\"routeId\":\"18\"");
        assertThat(json).contains("\"occurredAt\":\"2026-09-29T21:19:31.020Z\"").contains("\"link\":");
        assertThat(json).doesNotContain("audience\":\"PUBLIC\",\"occurred").doesNotContain("source_record_ts");
    }

    @Test
    @DisplayName("The JSON of each view is made once and reused")
    void cached() {
        HubEvent event = StreamFixtures.alert(AT, Audience.PUBLIC, "18");

        String first = writer.json(event, true);

        assertThat(writer.json(event, true)).isSameAs(first);
        assertThat(writer.json(event, false)).isNotSameAs(first);
    }

    @Test
    @DisplayName("SE-10 heartbeat and resync have no id line")
    void controlFrames() {
        String heartbeat = wire(writer.build(new Frame.Heartbeat(AT, AT)));
        String resync = wire(writer.build(
                new Frame.Resync(ResyncReason.BUFFER_EXPIRED, EnumSet.of(UiChannel.JOBS, UiChannel.ALERTS), AT)));

        assertThat(heartbeat).doesNotContain("id:").startsWith("event:heartbeat\ndata:");
        assertThat(heartbeat).contains("\"serverTime\":\"2026-09-29T21:19:31.020Z\"");
        assertThat(resync).doesNotContain("id:").startsWith("event:resync\ndata:");
        assertThat(resync).contains("\"reason\":\"BUFFER_EXPIRED\"").contains("\"channels\":[\"alerts\",\"jobs\"]");
        assertThat(wire(writer.build(new Frame.Retry(1000)))).isEqualTo("retry:1000\n\n");
    }

    @Test
    void anonymousViewOfAnUnprojectedTypeIsEmpty() {
        HubEvent event = HubEvent.received(
                StreamFixtures.ulid(AT), "x.y", UiChannel.ALERTS, Audience.PUBLIC, AT, null, null, Map.of("k", 1));

        assertThat(writer.json(event, true)).contains("\"data\":{}");
    }
}
