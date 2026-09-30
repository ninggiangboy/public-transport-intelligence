package dev.pti.apitest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stands in for the Alertmanager webhook handler of E-80, which the alert slice (P4-11) builds: it answers 204 on the
 * real path so that the token authentication of {@code /internal/**} can be tested through HTTP. Delete it, and the
 * {@code @Import} of it, when that handler exists.
 */
public final class StubWebhook {

    private StubWebhook() {}

    @RestController
    public static class StubWebhookController {

        @PostMapping("/internal/alerts/alertmanager")
        public ResponseEntity<Void> receive() {
            return ResponseEntity.noContent().build();
        }
    }
}
