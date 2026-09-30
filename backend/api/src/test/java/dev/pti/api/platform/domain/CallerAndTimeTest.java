package dev.pti.api.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CallerAndTimeTest {

    @Test
    void anonymousCallerHasNoRolesAndNoActor() {
        Caller anonymous = Caller.anonymous();

        assertThat(anonymous.authenticated()).isFalse();
        assertThat(anonymous.isViewer()).isFalse();
        assertThat(anonymous.isOperator()).isFalse();
        assertThatIllegalStateException().isThrownBy(anonymous::actor);
    }

    @Test
    @DisplayName("The actor is user:<preferred_username> (DOC-27 §3.2)")
    void actorHasTheAuditForm() {
        Caller operator = new Caller("operator", "Demo Operator", EnumSet.allOf(Role.class), Instant.EPOCH);

        assertThat(operator.actor()).isEqualTo("user:operator");
        assertThat(operator.isOperator()).isTrue();
        assertThat(operator.isViewer()).isTrue();
    }

    @Test
    void anonymousCallerCannotHoldRoles() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Caller(null, null, Set.of(Role.VIEWER), null));
    }

    @Test
    void rolesAreReadFromTheirWireNames() {
        assertThat(Role.fromWireName("viewer")).contains(Role.VIEWER);
        assertThat(Role.fromWireName("operator")).contains(Role.OPERATOR);
        assertThat(Role.fromWireName("offline_access")).isEqualTo(Optional.empty());
        assertThat(Role.OPERATOR.authority()).isEqualTo("ROLE_OPERATOR");
    }

    @Test
    @DisplayName("DOC-31 §4.1 instants are cut to milliseconds and written without a fraction when it is zero")
    void timeFormat() {
        assertThat(ApiTime.format(Instant.parse("2026-09-29T21:19:30.107623Z"))).isEqualTo("2026-09-29T21:19:30.107Z");
        assertThat(ApiTime.format(Instant.parse("2026-09-29T21:19:30.000400Z"))).isEqualTo("2026-09-29T21:19:30Z");
        assertThat(ApiTime.format(Instant.parse("2026-09-29T21:19:30Z"))).isEqualTo("2026-09-29T21:19:30Z");
        assertThat(ApiTime.truncate(Instant.parse("2026-09-29T21:19:30.9999Z")))
                .isEqualTo(Instant.parse("2026-09-29T21:19:30.999Z"));
    }

    @Test
    void noActiveFeedIsA503WithRetryAfter30() {
        ServiceUnavailableException exception = ServiceUnavailableException.noActiveFeed();

        assertThat(exception.type()).isEqualTo(ProblemType.SERVICE_UNAVAILABLE);
        assertThat(exception.getMessage()).isEqualTo("No active GTFS feed yet.");
        assertThat(exception.retryAfterSeconds()).isEqualTo(30);
    }

    @Test
    void validationExceptionCarriesItsFields() {
        ValidationException exception = ValidationException.of("limit", "must be between 1 and 500");

        assertThat(exception.type()).isEqualTo(ProblemType.VALIDATION_ERROR);
        assertThat(exception.errors())
                .containsExactly(new ApiException.FieldError("limit", "must be between 1 and 500"));
        assertThat(new ForbiddenException("no").type()).isEqualTo(ProblemType.FORBIDDEN);
        assertThat(new NotFoundException("gone").type()).isEqualTo(ProblemType.NOT_FOUND);
    }

    @Test
    void withAsOfTakesAnOptional() {
        assertThat(WithAsOf.of("x", Optional.empty()).asOf()).isNull();
        assertThat(WithAsOf.of("x", Optional.of(Instant.EPOCH)).asOf()).isEqualTo(Instant.EPOCH);
    }
}
