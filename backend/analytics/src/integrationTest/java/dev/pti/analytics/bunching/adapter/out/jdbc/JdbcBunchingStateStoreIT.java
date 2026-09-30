package dev.pti.analytics.bunching.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.bunching.application.port.BunchingStateStore.StoredState;
import dev.pti.analytics.bunching.domain.BunchingEpisode;
import dev.pti.analytics.bunching.domain.PairState;
import dev.pti.analytics.bunching.domain.PairStateChanges;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The state tables of DOC-23 §5.8 and the tick query of §4.2 as {@code etl_writer}. */
class JdbcBunchingStateStoreIT {

    private static final Instant T = Instant.parse("2026-09-29T12:03:30Z");

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcBunchingStateStore store = new JdbcBunchingStateStore(db.jdbc);
    private final JdbcBunchingEpisodeWriter episodes = new JdbcBunchingEpisodeWriter(db.jdbc);
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String counting = "AN2S-C-" + suffix;
    private final String following = "AN2S-F-" + suffix;
    private final String orphan = "AN2S-O-" + suffix;

    @AfterEach
    void cleanUp() {
        for (String route : List.of(counting, following, orphan)) {
            db.jdbc
                    .sql("DELETE FROM insight.analytics_bunching_pair_state WHERE route_id = :r")
                    .param("r", route)
                    .update();
            db.jdbc
                    .sql("DELETE FROM insight.insight_bus_bunching WHERE route_id = :r")
                    .param("r", route)
                    .update();
            db.jdbc
                    .sql("DELETE FROM insight.analytics_bunching_cursor WHERE route_id = :r")
                    .param("r", route)
                    .update();
        }
    }

    private static PairState counting(int direction, int consecutive) {
        return new PairState(direction, "L", "F", "TL", "TF", consecutive, T, 250, "S4", null, T);
    }

    private BunchingEpisode open(String route) {
        Instant start = T.minusSeconds(15);
        return new BunchingEpisode(
                UUID.randomUUID(), route, 0, "L", "F", "TL", "TF", start, null, null, 600, 300, 250, 260, "S4", 2, T);
    }

    @Test
    void theCursorIsWrittenAndMovedForward() {
        assertThat(store.cursor(counting)).isEmpty();

        store.saveCursor(counting, T);
        store.saveCursor(counting, T.plusSeconds(15));

        assertThat(store.cursor(counting)).contains(T.plusSeconds(15));
    }

    @Test
    void pairStatesAreInsertedChangedAndDeletedByTheirKey() {
        store.save(counting, new PairStateChanges(List.of(counting(0, 1)), List.of()));
        assertThat(store.load(counting).pairStates()).containsExactly(counting(0, 1));

        store.save(counting, new PairStateChanges(List.of(counting(0, 2)), List.of()));
        assertThat(store.load(counting).pairStates()).containsExactly(counting(0, 2));

        store.save(counting, new PairStateChanges(List.of(), List.of(counting(0, 2))));
        assertThat(store.load(counting).pairStates()).isEmpty();
    }

    @Test
    void aStateThatFollowsAnOpenEpisodeLoadsTheEpisodeToo() {
        BunchingEpisode episode = open(following);
        episodes.upsert(episode, UUID.randomUUID());
        PairState state = new PairState(0, "L", "F", "TL", "TF", 2, episode.episodeStart(), 250, "S4", episode.id(), T);
        store.save(following, new PairStateChanges(List.of(state), List.of()));

        StoredState loaded = store.load(following);

        assertThat(loaded.pairStates()).containsExactly(state);
        assertThat(loaded.openEpisodes()).containsExactly(episode);
    }

    @Test
    void anOpenEpisodeNoStateFollowsIsNotLoadedButStillNeedsTheTick() {
        BunchingEpisode episode = open(orphan);
        episodes.upsert(episode, UUID.randomUUID());

        assertThat(store.load(orphan).openEpisodes()).isEmpty();
        assertThat(store.routesNeedingTick()).contains(orphan);
    }

    @Test
    void aRouteWithAPairStateNeedsTheTickAndOneWithoutDoesNot() {
        store.save(counting, new PairStateChanges(List.of(counting(0, 1)), List.of()));

        assertThat(store.routesNeedingTick()).contains(counting).doesNotContain(following);
    }
}
