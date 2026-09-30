package dev.pti.api.platform.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.api.platform.adapter.in.security.EndpointRules.Access;
import dev.pti.api.platform.adapter.in.security.EndpointRules.Rule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** The matrix itself (DOC-27 §4, DOC-32 §2), read against the design document. */
class EndpointRulesTest {

    private static final Pattern DOC_ROW =
            Pattern.compile("^\\| (E-\\d\\d) \\| `(GET|POST|PUT) ([^`]+)` \\| ([^|]+?) \\|");

    @Test
    @DisplayName("Every endpoint of DOC-32 §2, but the webhook and the simulator proxy, has a rule with its method")
    void everyDocumentedEndpointIsInTheMatrix() throws IOException {
        Path doc = Path.of(System.getProperty("pti.repo-root"), "docs/07-api/api-endpoints.md");
        Set<String> documented = new HashSet<>();
        for (String line : Files.readAllLines(doc)) {
            Matcher row = DOC_ROW.matcher(line);
            if (row.find() && !row.group(1).equals("E-80")) {
                documented.add(row.group(1) + " " + row.group(2) + " /api/v1" + row.group(3));
            }
        }
        Set<String> implemented = new HashSet<>();
        for (Rule rule : EndpointRules.api(true)) {
            if (rule.method() != null && rule.when() == null) {
                implemented.add(rule.id() + " " + rule.method() + " " + rule.pattern());
            }
        }

        assertThat(implemented).containsAll(documented);
    }

    @Test
    @DisplayName("The role of each rule is the one of the DOC-32 §2 table, with the stated exceptions")
    void rolesMatchTheDocument() throws IOException {
        Path doc = Path.of(System.getProperty("pti.repo-root"), "docs/07-api/api-endpoints.md");
        for (String line : Files.readAllLines(doc)) {
            Matcher row = DOC_ROW.matcher(line);
            if (!row.find() || row.group(1).equals("E-80") || row.group(1).equals("E-70")) {
                continue;
            }
            String role = row.group(4);
            Access expected = role.startsWith("anonymous")
                    ? Access.ANONYMOUS
                    : role.startsWith("viewer") ? Access.VIEWER : Access.OPERATOR;
            String path = "/api/v1" + row.group(3).replaceAll("\\{[^/}]+}", "x");
            assertThat(EndpointRules.accessFor(EndpointRules.api(true), HttpMethod.valueOf(row.group(2)), path))
                    .as(row.group(1) + " " + row.group(2) + " " + path)
                    .contains(expected);
        }
    }

    @Test
    void theSimulatorProxyExistsOnlyInTheDemoProfile() {
        assertThat(EndpointRules.api(false)).noneMatch(rule -> rule.id().equals("E-90"));
        assertThat(EndpointRules.api(true))
                .filteredOn(rule -> rule.id().equals("E-90"))
                .singleElement()
                .satisfies(rule -> {
                    assertThat(rule.access()).isEqualTo(Access.OPERATOR);
                    assertThat(rule.method()).isNull();
                });
    }

    @Test
    void theWebhookIsTheOnlyInternalRule() {
        List<Rule> internal = EndpointRules.internal();

        assertThat(internal).singleElement().satisfies(rule -> {
            assertThat(rule.method()).isEqualTo(HttpMethod.POST);
            assertThat(rule.pattern()).isEqualTo("/internal/alerts/alertmanager");
            assertThat(rule.access()).isEqualTo(Access.WEBHOOK);
        });
    }

    @Test
    @DisplayName("More specific patterns come first: delays before the route, estimate before {id}")
    void specificRulesPrecedeGeneralOnes() {
        List<Rule> rules = EndpointRules.api(false);

        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/routes/18/delays"))
                .contains(Access.VIEWER);
        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/routes/18"))
                .contains(Access.ANONYMOUS);
        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/etl/replays/estimate"))
                .contains(Access.OPERATOR);
        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/etl/replays/0192f5a1"))
                .contains(Access.VIEWER);
    }

    @Test
    void undeclaredMethodsAndPathsMatchNothing() {
        List<Rule> rules = EndpointRules.api(false);

        assertThat(EndpointRules.accessFor(rules, HttpMethod.DELETE, "/api/v1/routes"))
                .isEmpty();
        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/ops/job-runs"))
                .isEmpty();
        assertThat(EndpointRules.accessFor(rules, HttpMethod.GET, "/api/v1/sim/status"))
                .isEmpty();
    }

    @Test
    void sampleCoversThePattern() {
        for (Rule rule : EndpointRules.api(true)) {
            assertThat(rule.covers(rule.method() != null ? rule.method() : HttpMethod.GET, rule.samplePath()))
                    .as(rule.id() + " " + rule.pattern())
                    .isTrue();
        }
    }
}
