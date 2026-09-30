package dev.pti.apitest;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.PagedResponse;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stand-ins for the endpoints of the slices that come after the platform, on paths the endpoint matrix already
 * covers ({@code /stops/{stopId}}, {@code /alerts/{id}/ack}, …) but that no real controller will take, so that the
 * platform's behaviour can be tested through HTTP. They are imported by the tests that need them and are not
 * component-scanned: this package is outside {@code dev.pti.api}.
 */
public final class StubEndpoints {

    private StubEndpoints() {}

    /** A body with one required field, to see validation and unknown-field errors. */
    public record AckBody(@NotBlank String note) {}

    @RestController
    public static class StubController {

        private final PageParams paging;
        private final CursorCodec codec;

        public StubController(PageParams paging, CursorCodec codec) {
            this.paging = paging;
            this.codec = codec;
        }

        /** 120 numbered rows, keyset paged; {@code status} is a filter that enters the cursor fingerprint. */
        @GetMapping(ApiPaths.V1 + "/stops/stub-list")
        public PagedResponse<String> list(
                @RequestParam(required = false) List<String> status,
                @RequestParam(required = false) Integer limit,
                @RequestParam(required = false) String cursor) {
            String fingerprint = CursorCodec.fingerprint(Map.of("status", status == null ? List.of() : status));
            PageRequest request = paging.resolve(limit, cursor, fingerprint);
            int from = request.after() == null
                    ? 0
                    : Integer.parseInt(request.after().keys().get(0)) + 1;
            List<String> rows = new ArrayList<>();
            for (int i = from; i < Math.min(120, from + request.fetchSize()); i++) {
                rows.add(String.format("%03d", i));
            }
            Page<String> page = Page.of(request, rows, row -> List.of(String.valueOf(Integer.parseInt(row))));
            return paging.respond(page, row -> row, fingerprint);
        }

        @GetMapping(ApiPaths.V1 + "/stops/stub-boom")
        public String boom() {
            throw new NullPointerException("secret internal detail");
        }

        @GetMapping(ApiPaths.V1 + "/stops/stub-missing")
        public String missing() {
            throw new NotFoundException("The stop does not exist.");
        }

        @GetMapping(ApiPaths.V1 + "/stops/stub-timeout")
        public String timeout() {
            throw new QueryTimeoutException("statement timeout", new SQLException("canceling statement", "57014"));
        }

        @GetMapping(ApiPaths.V1 + "/stops/n-{value}")
        public String number(@PathVariable int value) {
            return String.valueOf(value);
        }

        @GetMapping(ApiPaths.V1 + "/stops/stub-required")
        public String required(@RequestParam String needed) {
            return needed;
        }

        @GetMapping(ApiPaths.V1 + "/stops/stub-type")
        public String type(@RequestParam int count) {
            return String.valueOf(count);
        }

        /** A handler that nobody declared in the endpoint matrix: SEC-03 expects it to be closed. */
        @GetMapping(ApiPaths.V1 + "/undeclared-stub")
        public String undeclared() {
            return "should never be reached";
        }

        /** Needs a viewer: the rule of {@code /insights/bunching/{id}}. */
        @GetMapping(ApiPaths.V1 + "/insights/bunching/stub-caller")
        public Map<String, Object> caller(Caller caller) {
            return Map.of("actor", caller.authenticated() ? caller.actor() : "none", "operator", caller.isOperator());
        }

        /** Needs an operator: the rule of {@code /alerts/{id}/ack}. */
        @PostMapping(ApiPaths.V1 + "/alerts/stub-ack/ack")
        public ResponseEntity<Map<String, String>> ack(@Valid @RequestBody AckBody body) {
            return ResponseEntity.ok(Map.of("note", body.note()));
        }

        /** Needs an operator: the rule of {@code /etl/flags/{key}}. */
        @PutMapping(ApiPaths.V1 + "/etl/flags/stub-flag")
        public Map<String, String> flag(@RequestBody Map<String, Object> body) {
            return Map.of("size", String.valueOf(body.size()));
        }
    }
}
