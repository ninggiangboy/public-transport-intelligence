package dev.pti.api.insight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.insight.domain.DisruptionEpisode;
import dev.pti.api.insight.domain.InsightFixtures;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.Role;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryInsight;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The public view of disruption episodes (DOC-32 E-12, E-13, EP-11). */
class DisruptionUseCasesTest {

    private static final Caller VIEWER = new Caller("viewer", null, Set.of(Role.VIEWER), null);
    private static final UUID PUBLIC_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIDDEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final InMemoryInsight insight = new InMemoryInsight();
    private ListDisruptionEpisodes list;
    private GetDisruptionEpisode get;

    @BeforeEach
    void setUp() {
        Instant start = Instant.parse("2026-09-29T20:58:00Z");
        insight.disruptions.add(InsightFixtures.disruption(PUBLIC_ID, "18", start, "PUBLIC", true));
        insight.disruptions.add(
                InsightFixtures.disruption(HIDDEN_ID, "18", start.minusSeconds(60), "ENGINEERING", true));
        DirectTransactions tx = new DirectTransactions();
        list = new ListDisruptionEpisodes(insight.disruptionReader, reading -> Optional.empty(), tx);
        get = new GetDisruptionEpisode(insight.disruptionReader, reading -> Optional.empty(), tx);
    }

    private static DisruptionQuery query() {
        return new DisruptionQuery(
                Instant.parse("2026-09-29T00:00:00Z"), Instant.parse("2026-09-30T00:00:00Z"), List.of(), null, false);
    }

    @Test
    @DisplayName("EP-11 an anonymous caller gets the public episodes only; a viewer gets all of them")
    void listByCaller() {
        Page<DisruptionEpisode> anonymous =
                list.execute(Caller.anonymous(), query(), PageRequest.first(50)).value();
        Page<DisruptionEpisode> viewer =
                list.execute(VIEWER, query(), PageRequest.first(50)).value();

        assertThat(anonymous.items()).extracting(DisruptionEpisode::id).containsExactly(PUBLIC_ID);
        assertThat(viewer.items()).extracting(DisruptionEpisode::id).containsExactly(PUBLIC_ID, HIDDEN_ID);
    }

    @Test
    @DisplayName("EP-11 an episode that is not public is a 404 for an anonymous caller, the same as an unknown id")
    void detailByCaller() {
        assertThat(get.execute(Caller.anonymous(), PUBLIC_ID).value().id()).isEqualTo(PUBLIC_ID);
        assertThat(get.execute(VIEWER, HIDDEN_ID).value().id()).isEqualTo(HIDDEN_ID);
        assertThatThrownBy(() -> get.execute(Caller.anonymous(), HIDDEN_ID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> get.execute(Caller.anonymous(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> get.execute(VIEWER, UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }
}
