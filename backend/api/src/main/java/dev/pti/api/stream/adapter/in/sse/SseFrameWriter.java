package dev.pti.api.stream.adapter.in.sse;

import dev.pti.api.stream.domain.Frame;
import dev.pti.api.stream.domain.HubEvent;
import dev.pti.api.stream.domain.SseProjection;
import dev.pti.common.events.UiChannel;
import dev.pti.common.time.Timestamps;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The SSE frames of DOC-33 §2.2, §5.9 and §5.10: {@code id:} and {@code event:} lines and one line of compact JSON.
 * The JSON of an event is made once per view and kept on the event for the other connections (DOC-26 §3).
 * {@code heartbeat} and {@code resync} have no {@code id:}, so that they never move the client's
 * {@code Last-Event-ID}.
 */
public final class SseFrameWriter {

    private final JsonMapper mapper;

    public SseFrameWriter(JsonMapper mapper) {
        this.mapper = mapper;
    }

    SseEmitter.SseEventBuilder build(Frame frame) {
        return switch (frame) {
            case Frame.Retry retry -> SseEmitter.event().reconnectTime(retry.millis());
            case Frame.Event event ->
                SseEmitter.event()
                        .id(event.event().id())
                        .name(event.event().type())
                        .data(json(event.event(), event.anonymous()));
            case Frame.Resync resync -> SseEmitter.event().name("resync").data(resync(resync));
            case Frame.Heartbeat heartbeat ->
                SseEmitter.event().name("heartbeat").data(heartbeat(heartbeat));
        };
    }

    /** {@code id}, {@code type}, {@code channel}, {@code occurredAt}, {@code routeId} and the projected data. */
    String json(HubEvent event, boolean anonymous) {
        String cached = event.frames().get(anonymous);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> data = anonymous
                ? SseProjection.forAnonymous(event.type(), event.data()).orElse(Map.of())
                : SseProjection.forViewer(event.type(), event.data());
        ObjectNode node = mapper.createObjectNode();
        node.put("id", event.id());
        node.put("type", event.type());
        node.put("channel", event.channel().wireName());
        node.put("occurredAt", Timestamps.format(event.occurredAt()));
        if (event.routeId() != null) {
            node.put("routeId", event.routeId());
        }
        node.set("data", mapper.valueToTree(data));
        String json = mapper.writeValueAsString(node);
        event.frames().put(anonymous, json);
        return json;
    }

    String resync(Frame.Resync resync) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "resync");
        node.put("occurredAt", Timestamps.format(resync.occurredAt()));
        ObjectNode data = node.putObject("data");
        data.put("reason", resync.reason().name());
        ArrayNode channels = data.putArray("channels");
        for (UiChannel channel : UiChannel.values()) {
            if (resync.channels().contains(channel)) {
                channels.add(channel.wireName());
            }
        }
        return mapper.writeValueAsString(node);
    }

    String heartbeat(Frame.Heartbeat heartbeat) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "heartbeat");
        ObjectNode data = node.putObject("data");
        data.put("serverTime", Timestamps.format(heartbeat.serverTime()));
        data.put("businessNow", Timestamps.format(heartbeat.businessNow()));
        return mapper.writeValueAsString(node);
    }
}
