package dev.pti.analytics.core.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.core.domain.Detector;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JdbcOpenEpisodeCountsIT {

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcOpenEpisodeCounts counts = new JdbcOpenEpisodeCounts(db.jdbc);
    private final UUID episode = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        db.jdbc
                .sql("DELETE FROM insight.insight_bus_bunching WHERE id = :id")
                .param("id", episode)
                .update();
    }

    @Test
    void anOpenBunchingEpisodeIsCountedAndAClosedOneIsNot() {
        long before = counts.count(Detector.BUNCHING);

        db.jdbc.sql("""
                        INSERT INTO insight.insight_bus_bunching (id, route_id, direction_id, vehicle_leader,
                          vehicle_follower, trip_leader, trip_follower, episode_start, status,
                          scheduled_headway_seconds, threshold_seconds, min_gap_seconds, last_gap_seconds,
                          last_evaluated_at, batch_id)
                        VALUES (:id, 'AN1-R', 0, 'an1-1', 'an1-2', 't1', 't2', TIMESTAMPTZ '2026-09-29 21:00:00Z',
                          'OPEN', 600, 300, 120, 120, TIMESTAMPTZ '2026-09-29 21:00:30Z', gen_random_uuid())""").param("id", episode).update();

        assertThat(counts.count(Detector.BUNCHING)).isEqualTo(before + 1);

        db.jdbc.sql("""
                        UPDATE insight.insight_bus_bunching
                        SET status = 'CLOSED', close_reason = 'GAP_RECOVERED',
                            episode_end = TIMESTAMPTZ '2026-09-29 21:05:00Z'
                        WHERE id = :id""").param("id", episode).update();

        assertThat(counts.count(Detector.BUNCHING)).isEqualTo(before);
    }

    @Test
    void disruptionEpisodesAreCountedToo() {
        assertThat(counts.count(Detector.DISRUPTION)).isNotNegative();
    }

    @Test
    void otherDetectorsKeepNoEpisodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> counts.count(Detector.ETA));
    }
}
