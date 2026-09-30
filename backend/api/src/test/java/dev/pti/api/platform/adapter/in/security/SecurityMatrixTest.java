package dev.pti.api.platform.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import dev.pti.api.platform.adapter.in.security.EndpointRules.Access;
import dev.pti.api.platform.adapter.in.security.EndpointRules.Rule;
import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SEC-01 (DOC-27 §14): every row of the endpoint matrix against an anonymous caller, a viewer and an operator. The
 * cases come from {@link EndpointRules} itself, so a row added there is tested without touching this class.
 *
 * <p>The check is on the authorization decision: a caller who is not allowed gets 401 (anonymous) or 403 (viewer),
 * one who is allowed gets anything else. Where no controller exists yet that is a plain 404. A later slice that adds
 * its controller changes nothing here.
 */
@ResourceLock(value = "in-memory-transit", mode = ResourceAccessMode.READ)
class SecurityMatrixTest extends ApiWebTestSupport {

    private enum Caller {
        ANONYMOUS,
        VIEWER,
        OPERATOR
    }

    @TestFactory
    @DisplayName("SEC-01 every endpoint of the matrix x {anonymous, viewer, operator}")
    Stream<DynamicTest> matrix() {
        List<Rule> rules = EndpointRules.api(false);
        return rules.stream()
                .flatMap(rule -> Stream.of(Caller.values())
                        .map(caller -> dynamicTest(
                                "%s %s %s as %s".formatted(rule.id(), rule.method(), rule.pattern(), caller),
                                () -> check(rule, caller))));
    }

    private void check(Rule rule, Caller caller) throws Exception {
        MockHttpServletResponse response =
                mvc.perform(build(rule, caller)).andReturn().getResponse();

        Access needed = rule.access();
        boolean allowed =
                switch (caller) {
                    case ANONYMOUS -> needed == Access.ANONYMOUS;
                    case VIEWER -> needed == Access.ANONYMOUS || needed == Access.VIEWER;
                    case OPERATOR -> true;
                };
        if (allowed) {
            assertThat(response.getStatus()).isNotIn(401, 403);
        } else {
            assertThat(response.getStatus()).isEqualTo(caller == Caller.ANONYMOUS ? 401 : 403);
            assertThat(response.getContentType()).startsWith("application/problem+json");
        }
    }

    private static MockHttpServletRequestBuilder build(Rule rule, Caller caller) {
        MockHttpServletRequestBuilder builder = request(rule.method(), rule.samplePath());
        if (rule.when() != null) {
            builder.param("channels", "jobs");
        }
        return switch (caller) {
            case ANONYMOUS -> builder;
            case VIEWER -> builder.header("Authorization", JwtFixture.bearer(JwtFixture.viewer()));
            case OPERATOR -> builder.header("Authorization", JwtFixture.bearer(JwtFixture.operator()));
        };
    }

    @Test
    @DisplayName("E-70 /stream without channels, or with only public ones, is open to anonymous callers")
    void streamPublicChannels() throws Exception {
        assertThat(mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/stream"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isNotIn(401, 403);
        assertThat(mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/stream")
                                .param("channels", "vehicles,alerts"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isNotIn(401, 403);
    }

    @Test
    @DisplayName("E-70 a repeated or comma-separated channels parameter that names jobs or dlq needs a viewer")
    void streamRestrictedChannels() throws Exception {
        assertThat(mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/stream")
                                .param("channels", "vehicles", "dlq"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/v1/stream")
                                .param("channels", "vehicles,jobs"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }
}
