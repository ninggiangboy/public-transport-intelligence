package dev.pti.api.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertFixtures;
import dev.pti.api.alert.domain.AlertState;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ForbiddenException;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.apitest.DirectTransactions;
import dev.pti.common.events.Audience;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The audience rules of {@code GET /alerts} (DOC-32 E-20, ADR-0023, EP-15). */
class ListAlertsTest extends AlertUseCaseSupport {

    private static final Caller VIEWER = new Caller("viewer", null, Set.of(Role.VIEWER), null);

    private ListAlerts list;

    @BeforeEach
    void setUp() {
        list = new ListAlerts(alerts.reader, new DirectTransactions());
        alerts.add(AlertFixtures.disruption());
        alerts.add(AlertFixtures.bunching(Instant.parse("2026-09-29T21:12:31Z")));
        alerts.add(AlertFixtures.of(AlertType.INFRA, Audience.ENGINEERING, null, java.util.Map.of()));
    }

    private static AlertQuery query(Set<Audience> audiences) {
        return new AlertQuery(
                audiences,
                List.of(),
                List.of(),
                List.of(),
                AlertState.ALL,
                Instant.parse("2026-09-28T00:00:00Z"),
                Instant.parse("2026-09-30T00:00:00Z"),
                null);
    }

    @Test
    @DisplayName("An anonymous caller sees the public alerts only, projected: no acknowledgement, a cut body")
    void anonymous() {
        WithAsOf<Page<Alert>> result = list.execute(Caller.anonymous(), query(Set.of()), PageRequest.first(50));

        assertThat(result.value().items()).singleElement().satisfies(alert -> {
            assertThat(alert.audience()).isEqualTo(Audience.PUBLIC);
            assertThat(alert.acknowledgedBy()).isNull();
            assertThat(alert.body()).doesNotContainKeys("zScore", "likelyCause");
        });
    }

    @Test
    @DisplayName("EP-15 an anonymous caller who asks for another audience is refused, not given an empty list")
    void anonymousAsksForOperations() {
        assertThatThrownBy(() ->
                        list.execute(Caller.anonymous(), query(Set.of(Audience.OPERATIONS)), PageRequest.first(50)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> list.execute(
                        Caller.anonymous(),
                        query(Set.of(Audience.PUBLIC, Audience.ENGINEERING)),
                        PageRequest.first(50)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("An anonymous caller may ask for PUBLIC explicitly")
    void anonymousAsksForPublic() {
        assertThat(list.execute(Caller.anonymous(), query(Set.of(Audience.PUBLIC)), PageRequest.first(50))
                        .value()
                        .items())
                .hasSize(1);
    }

    @Test
    @DisplayName("A viewer sees every audience, in full, and may narrow to some")
    void viewer() {
        Page<Alert> all =
                list.execute(VIEWER, query(Set.of()), PageRequest.first(50)).value();
        Page<Alert> engineering = list.execute(VIEWER, query(Set.of(Audience.ENGINEERING)), PageRequest.first(50))
                .value();

        assertThat(all.items()).hasSize(3);
        assertThat(all.items())
                .anySatisfy(alert -> assertThat(alert.acknowledgedBy()).isEqualTo("user:operator"));
        assertThat(engineering.items()).singleElement().extracting(Alert::type).isEqualTo(AlertType.INFRA);
    }

    @Test
    @DisplayName("X-Data-As-Of is the newest created_at in the page, and none for an empty page")
    void asOf() {
        Instant newest = Instant.parse("2026-09-29T21:12:31Z");

        assertThat(list.execute(VIEWER, query(Set.of()), PageRequest.first(50)).asOf())
                .isEqualTo(newest);
        assertThat(list.execute(VIEWER, query(Set.of(Audience.ENGINEERING)), PageRequest.first(50))
                        .asOf())
                .isEqualTo(AlertFixtures.CREATED);
        AlertQuery empty = new AlertQuery(
                Set.of(),
                List.of(),
                List.of(),
                List.of(),
                AlertState.ALL,
                Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"),
                null);
        assertThat(list.execute(VIEWER, empty, PageRequest.first(50)).asOf()).isNull();
    }
}
