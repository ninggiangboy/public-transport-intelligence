package dev.pti.api.alert.adapter.in.web;

import dev.pti.api.alert.application.ReceiveAlertmanagerWebhook;
import dev.pti.api.alert.application.port.WebhookMetrics;
import dev.pti.api.alert.domain.AlertmanagerAlert;
import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.domain.ValidationException;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * E-80 {@code POST /internal/alerts/alertmanager} (DOC-32 §11): the webhook of Alertmanager. It is inside the network,
 * not proxied by nginx, and not in the public OpenAPI document; the token is checked before the request gets here
 * ({@code WebhookTokenFilter}, DOC-27 §6), and the rate limit does not apply to it.
 *
 * <p>The body is read as text and parsed here, so that a body that is not JSON, or has no well formed {@code alerts},
 * is counted as {@code rejected} and answered with a 400, and Alertmanager sends it again.
 */
@RestController
@Hidden
class AlertmanagerWebhookController {

    private final ReceiveAlertmanagerWebhook receive;
    private final WebhookMetrics metrics;
    private final JsonMapper json;

    AlertmanagerWebhookController(ReceiveAlertmanagerWebhook receive, WebhookMetrics metrics, JsonMapper json) {
        this.receive = receive;
        this.metrics = metrics;
        this.json = json;
    }

    @PostMapping(ApiPaths.INTERNAL + "/alerts/alertmanager")
    ResponseEntity<Void> receiveAlertmanagerWebhook(@RequestBody String body) {
        List<AlertmanagerAlert> notices = parse(body);
        receive.execute(notices);
        return ResponseEntity.noContent().build();
    }

    private List<AlertmanagerAlert> parse(String body) {
        try {
            return json.readValue(body, AlertmanagerPayload.class).toAlerts();
        } catch (JacksonException e) {
            metrics.outcome(WebhookMetrics.REJECTED);
            throw ValidationException.of("body", "is not an Alertmanager webhook payload");
        } catch (ValidationException e) {
            metrics.outcome(WebhookMetrics.REJECTED);
            throw e;
        }
    }
}
