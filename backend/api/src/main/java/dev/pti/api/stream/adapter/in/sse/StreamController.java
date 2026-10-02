package dev.pti.api.stream.adapter.in.sse;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ServiceUnavailableException;
import dev.pti.api.platform.domain.ValidationException;
import dev.pti.api.stream.application.Connection;
import dev.pti.api.stream.application.EventHub;
import dev.pti.api.stream.domain.CloseReason;
import dev.pti.api.stream.domain.Subscription;
import dev.pti.common.events.UiChannel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * E-70 {@code GET /stream} (DOC-32, DOC-26 §5, §7): the channels and route filter are checked, the caps applied and
 * the hub opens the connection, all before the {@code 200 text/event-stream} goes out, so that every refusal is a
 * normal Problem Details response. Whether {@code jobs} and {@code dlq} need a viewer is the endpoint matrix's job.
 */
@RestController
class StreamController {

    private static final Set<UiChannel> DEFAULT_CHANNELS = EnumSet.of(UiChannel.VEHICLES, UiChannel.ALERTS);

    private final EventHub hub;
    private final SseFrameWriter frames;
    private final StreamEndpointSettings settings;

    StreamController(EventHub hub, SseFrameWriter frames, StreamEndpointSettings settings) {
        this.hub = hub;
        this.frames = frames;
        this.settings = settings;
    }

    @GetMapping(path = ApiPaths.V1 + "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            operationId = "openEventStream",
            summary = "Server-sent events: vehicle positions, alerts, job runs and dead-letter changes (DOC-33)")
    @ApiResponse(
            responseCode = "200",
            description = "The event stream; a frame is an id, an event type and one line of JSON",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    ResponseEntity<SseEmitter> openEventStream(
            @Parameter(
                            description =
                                    "Channels, comma separated: vehicles, alerts, jobs, dlq (jobs and dlq need a viewer)")
                    @RequestParam(name = "channels", required = false)
                    @Nullable
                    List<String> channels,
            @Parameter(description = "Only these routes (at most 20); events without a route are always sent")
                    @RequestParam(name = "routeId", required = false)
                    @Nullable
                    List<String> routeIds,
            @Parameter(description = "The id of the last event received, to get what was missed")
                    @RequestHeader(name = "Last-Event-ID", required = false)
                    @Nullable
                    String lastEventId,
            Caller caller,
            HttpServletRequest request)
            throws InterruptedException {
        if (!settings.enabled()) {
            throw new ServiceUnavailableException("Real-time events are switched off on this server.", 30);
        }
        Subscription subscription = new Subscription(
                channels(channels),
                routes(routeIds),
                caller.authenticated(),
                caller.tokenExpiresAt(),
                caller.authenticated() ? "user:" + caller.username() : "ip:" + request.getRemoteAddr());
        SseEmitter emitter = new SseEmitter(settings.maxLifetime().toMillis());
        AtomicReference<@Nullable Connection> opened = new AtomicReference<>();
        emitter.onTimeout(() -> closeIfOpen(opened, CloseReason.TIMEOUT));
        emitter.onCompletion(() -> closeIfOpen(opened, CloseReason.CLIENT_GONE));
        emitter.onError(error -> closeIfOpen(opened, CloseReason.CLIENT_GONE));
        opened.set(hub.open(subscription, lastEventId, new SseEmitterSink(emitter, frames)));
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }

    private void closeIfOpen(AtomicReference<@Nullable Connection> opened, CloseReason reason) {
        Connection connection = opened.get();
        if (connection != null) {
            hub.close(connection, reason);
        }
    }

    private static Set<UiChannel> channels(@Nullable List<String> values) {
        Set<String> names = split(values);
        if (names.isEmpty()) {
            return DEFAULT_CHANNELS;
        }
        EnumSet<UiChannel> channels = EnumSet.noneOf(UiChannel.class);
        for (String name : names) {
            try {
                channels.add(UiChannel.fromWireName(name));
            } catch (IllegalArgumentException e) {
                throw ValidationException.of(
                        "channels", "unknown channel '" + name + "'; use vehicles, alerts, jobs, dlq");
            }
        }
        return channels;
    }

    private Set<String> routes(@Nullable List<String> values) {
        Set<String> routes = split(values);
        if (routes.size() > settings.maxRouteFilter()) {
            throw ValidationException.of("routeId", "at most " + settings.maxRouteFilter() + " routes");
        }
        return routes;
    }

    /** Repeated parameters and comma-separated values alike, trimmed, blanks dropped, in order. */
    private static Set<String> split(@Nullable List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        if (values == null) {
            return out;
        }
        values.stream()
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(out::add);
        return out;
    }
}
