package dev.pti.api.platform.adapter.in.security;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The endpoint × role matrix of DOC-27 §4 and DOC-32 §2, in one place. A rule says who may call a method and path;
 * the first rule that matches decides, and a request that matches none is denied (see {@link
 * EndpointAuthorizationManager}). The matrix already lists every endpoint of DOC-32, so a slice that adds a
 * controller adds no rule; {@code EndpointRulesTest} and the security matrix test read this list.
 *
 * <p>What a URL cannot say is checked in the use case, not here: the public view of disruptions, the audience of
 * alerts, the bunching overlay of {@code /vehicles/live}.
 */
public final class EndpointRules {

    /** The least role that may call an endpoint; an operator is also a viewer (role hierarchy). */
    public enum Access {
        ANONYMOUS,
        VIEWER,
        OPERATOR,
        /** The Alertmanager webhook token (DOC-27 §6). */
        WEBHOOK
    }

    /**
     * One row of the matrix.
     *
     * @param id the endpoint id of DOC-32, for example {@code E-03}
     * @param method {@code null} for every method
     * @param when an extra condition on the request, for example the channels of {@code /stream}; {@code null} if none
     */
    public record Rule(
            String id,
            @Nullable HttpMethod method,
            String pattern,
            Access access,
            @Nullable Predicate<HttpServletRequest> when) {

        private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

        public RequestMatcher matcher() {
            PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
            return method == null ? builder.matcher(pattern) : builder.matcher(method, pattern);
        }

        boolean matches(HttpServletRequest request, RequestMatcher matcher) {
            return matcher.matches(request) && (when == null || when.test(request));
        }

        /** True if the rule covers the method and path, ignoring conditions on the request (OpenAPI, tests). */
        public boolean covers(HttpMethod candidate, String path) {
            PathPattern parsed = PARSER.parse(pattern);
            return (method == null || method.equals(candidate)) && parsed.matches(PathContainer.parsePath(path));
        }

        /** A concrete path that the pattern matches: {@code {id}} becomes {@code x}, {@code /**} becomes {@code /x}. */
        public String samplePath() {
            return pattern.replaceAll("\\{[^/}]+}", "x").replace("/**", "/x");
        }
    }

    private static final Set<String> RESTRICTED_CHANNELS = Set.of("jobs", "dlq");

    private EndpointRules() {}

    /**
     * The rules of {@code /api/**}.
     *
     * @param demo whether the {@code demo} profile is active, which adds the simulator proxy (E-90); otherwise
     *     {@code /sim/**} matches no rule and is 404 for a caller who may see that it does not exist
     */
    public static List<Rule> api(boolean demo) {
        List<Rule> rules = new ArrayList<>();
        String v1 = ApiPaths.V1;

        // 4.1 Transit. More specific patterns first: delays needs a viewer, its siblings are public.
        rules.add(get("E-03", v1 + "/routes/{routeId}/delays", Access.VIEWER));
        rules.add(get("E-04", v1 + "/routes/{routeId}/delay-profile", Access.ANONYMOUS));
        rules.add(get("E-01", v1 + "/routes", Access.ANONYMOUS));
        rules.add(get("E-02", v1 + "/routes/{routeId}", Access.ANONYMOUS));
        rules.add(get("E-05", v1 + "/vehicles/live", Access.ANONYMOUS));
        rules.add(get("E-06", v1 + "/stops", Access.ANONYMOUS));
        rules.add(get("E-07", v1 + "/stops/{stopId}", Access.ANONYMOUS));
        rules.add(get("E-08", v1 + "/stops/{stopId}/arrivals", Access.ANONYMOUS));

        // 4.2 Insight.
        rules.add(get("E-10", v1 + "/insights/bunching", Access.VIEWER));
        rules.add(get("E-11", v1 + "/insights/bunching/{id}", Access.VIEWER));
        rules.add(get("E-12", v1 + "/insights/disruption", Access.ANONYMOUS));
        rules.add(get("E-13", v1 + "/insights/disruption/{id}", Access.ANONYMOUS));
        rules.add(get("E-14", v1 + "/insights/otp", Access.VIEWER));
        rules.add(get("E-15", v1 + "/insights/ticketing-anomalies", Access.VIEWER));
        rules.add(get("E-16", v1 + "/insights/ticketing-anomalies/{id}", Access.VIEWER));
        rules.add(get("E-17", v1 + "/insights/dispatch-suggestions", Access.VIEWER));
        rules.add(post("E-18", v1 + "/insights/dispatch-suggestions/{id}/feedback", Access.OPERATOR));

        // 4.3 Alerts.
        rules.add(get("E-20", v1 + "/alerts", Access.ANONYMOUS));
        rules.add(post("E-21", v1 + "/alerts/{id}/ack", Access.OPERATOR));

        // 4.4 Jobs.
        rules.add(get("E-30", v1 + "/etl/jobs", Access.VIEWER));
        rules.add(get("E-31", v1 + "/etl/jobs/summary", Access.VIEWER));
        rules.add(get("E-32", v1 + "/etl/jobs/{runId}", Access.VIEWER));
        rules.add(post("E-33", v1 + "/etl/jobs", Access.OPERATOR));
        rules.add(get("E-34", v1 + "/etl/job-requests/{id}", Access.VIEWER));
        rules.add(post("E-35", v1 + "/etl/jobs/{runId}/restart", Access.OPERATOR));
        rules.add(post("E-36", v1 + "/etl/jobs/{runId}/stop", Access.OPERATOR));
        rules.add(get("E-37", v1 + "/etl/batches/{batchId}", Access.VIEWER));
        rules.add(get("E-38", v1 + "/etl/feeds", Access.VIEWER));

        // 4.5 Dead letters.
        rules.add(get("E-40", v1 + "/etl/dlq", Access.VIEWER));
        rules.add(get("E-41", v1 + "/etl/dlq/summary", Access.VIEWER));
        rules.add(get("E-48", v1 + "/etl/dlq/actions", Access.VIEWER));
        rules.add(get("E-42", v1 + "/etl/dlq/{id}", Access.VIEWER));
        rules.add(put("E-43", v1 + "/etl/dlq/{id}/payload", Access.OPERATOR));
        rules.add(post("E-44", v1 + "/etl/dlq/{id}/replay", Access.OPERATOR));
        rules.add(post("E-45", v1 + "/etl/dlq/{id}/confirm", Access.OPERATOR));
        rules.add(post("E-46", v1 + "/etl/dlq/{id}/discard", Access.OPERATOR));
        rules.add(post("E-47", v1 + "/etl/dlq/{id}/resolve", Access.OPERATOR));

        // 4.6 Replays. The estimate is for whoever creates a replay, so it is an operator's; it must precede {id}.
        rules.add(post("E-50", v1 + "/etl/replays", Access.OPERATOR));
        rules.add(get("E-53", v1 + "/etl/replays/estimate", Access.OPERATOR));
        rules.add(get("E-51", v1 + "/etl/replays", Access.VIEWER));
        rules.add(get("E-52", v1 + "/etl/replays/{id}", Access.VIEWER));

        // 4.7 Runtime flags.
        rules.add(get("E-55", v1 + "/etl/flags", Access.VIEWER));
        rules.add(get("E-56", v1 + "/etl/flags/{key}", Access.VIEWER));
        rules.add(put("E-57", v1 + "/etl/flags/{key}", Access.OPERATOR));

        // 4.8 System.
        rules.add(get("E-60", v1 + "/system/freshness", Access.ANONYMOUS));
        rules.add(get("E-61", v1 + "/me", Access.ANONYMOUS));

        // 4.9 Real time: the channels jobs and dlq are for viewers, and the check comes before the stream opens.
        rules.add(new Rule(
                "E-70", HttpMethod.GET, v1 + "/stream", Access.VIEWER, EndpointRules::asksForRestrictedChannel));
        rules.add(get("E-70", v1 + "/stream", Access.ANONYMOUS));

        if (demo) {
            rules.add(new Rule("E-90", null, v1 + "/sim/**", Access.OPERATOR, null));
        }
        return List.copyOf(rules);
    }

    /** The rules of {@code /internal/**}: only the Alertmanager webhook, with its token (E-80). */
    public static List<Rule> internal() {
        return List.of(
                new Rule("E-80", HttpMethod.POST, ApiPaths.INTERNAL + "/alerts/alertmanager", Access.WEBHOOK, null));
    }

    /** The access the matrix gives a method and path, for the OpenAPI document; empty if no rule covers it. */
    public static java.util.Optional<Access> accessFor(List<Rule> rules, HttpMethod method, String path) {
        return rules.stream()
                .filter(rule -> rule.when() == null && rule.covers(method, path))
                .map(Rule::access)
                .findFirst();
    }

    private static boolean asksForRestrictedChannel(HttpServletRequest request) {
        String[] values = request.getParameterValues("channels");
        if (values == null) {
            return false;
        }
        return Arrays.stream(values)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .anyMatch(RESTRICTED_CHANNELS::contains);
    }

    private static Rule get(String id, String pattern, Access access) {
        return new Rule(id, HttpMethod.GET, pattern, access, null);
    }

    private static Rule post(String id, String pattern, Access access) {
        return new Rule(id, HttpMethod.POST, pattern, access, null);
    }

    private static Rule put(String id, String pattern, Access access) {
        return new Rule(id, HttpMethod.PUT, pattern, access, null);
    }
}
