package dev.pti.api.sim.adapter.out.http;

import dev.pti.api.sim.application.port.SimulatorGateway;
import dev.pti.api.sim.domain.SimulatorCall;
import dev.pti.api.sim.domain.SimulatorReply;
import dev.pti.api.sim.domain.SimulatorUnavailableException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The simulator over {@code java.net.http} (DOC-32 E-90): connect timeout 1 s, read timeout 5 s. It adds {@code
 * X-Requested-By} and {@code traceparent}, forwards the content type and the body, and never the {@code
 * Authorization} header, which the call does not carry.
 */
public final class HttpSimulatorGateway implements SimulatorGateway {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final String baseUrl;
    private final @Nullable Tracer tracer;

    public HttpSimulatorGateway(String baseUrl, @Nullable Tracer tracer) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.tracer = tracer;
    }

    @Override
    public SimulatorReply exchange(SimulatorCall call) {
        String rawQuery = call.query();
        String query = rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery;
        HttpRequest.BodyPublisher body = call.body().length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(call.body());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + call.path() + query))
                .timeout(READ_TIMEOUT)
                .header("X-Requested-By", call.requestedBy())
                .method(call.method(), body);
        if (call.contentType() != null) {
            request.header("Content-Type", call.contentType());
        }
        String traceparent = traceparent();
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        try {
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new SimulatorReply(
                    response.statusCode(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.body(),
                    response.headers().firstValue("Location").orElse(null));
        } catch (IOException e) {
            throw new SimulatorUnavailableException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SimulatorUnavailableException(e);
        }
    }

    private @Nullable String traceparent() {
        Span span = tracer == null ? null : tracer.currentSpan();
        return span == null
                ? null
                : "00-" + span.context().traceId() + "-" + span.context().spanId() + "-01";
    }
}
