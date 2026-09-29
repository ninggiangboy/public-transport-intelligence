package dev.pti.etl.batch.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.maintenance.BatchedPurgeTasklet.PurgeTarget;
import dev.pti.etl.batch.maintenance.PartitionMaintenanceTasklet.ManagedTable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.jdbc.core.JdbcTemplate;

class MaintenanceTaskletsTest {

    private static final Clock REAL = Clock.fixed(Instant.parse("2026-09-29T21:20:00Z"), ZoneOffset.UTC);

    private static StepContribution contribution() {
        return new StepContribution(MetaDataInstanceFactory.createStepExecution());
    }

    @Test
    void partitionsAreCreatedAheadAndDroppedPastRetentionOnTheBusinessDate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT dw.ensure_partitions(?, ?, ?)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(2);
        when(jdbc.queryForObject(eq("SELECT dw.drop_partitions_before(?, ?)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        PartitionMaintenanceTasklet tasklet = new PartitionMaintenanceTasklet(
                jdbc,
                new BusinessClock(REAL, Duration.ofHours(-24)),
                ZoneId.of("America/Chicago"),
                List.of(new ManagedTable("fact_vehicle_position", 7, Duration.ofDays(14))),
                meters);
        StepContribution contribution = contribution();

        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc)
                .queryForObject(
                        "SELECT dw.ensure_partitions(?, ?, ?)",
                        Integer.class,
                        "fact_vehicle_position",
                        LocalDate.parse("2026-09-27"),
                        LocalDate.parse("2026-10-05"));
        verify(jdbc)
                .queryForObject(
                        "SELECT dw.drop_partitions_before(?, ?)",
                        Integer.class,
                        "fact_vehicle_position",
                        LocalDate.parse("2026-09-14"));
        assertThat(contribution.getExitStatus().getExitDescription())
                .isEqualTo("Business date 2026-09-28; fact_vehicle_position: created 2, dropped 0");
        assertThat(meters.find("pti.retention.deleted").counter()).isNull();
    }

    @Test
    void thePurgeDeletesOneBatchPerCallAndMovesOnWhenATableIsDone() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(10, 4, 0);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        BatchedPurgeTasklet tasklet = new BatchedPurgeTasklet(
                jdbc,
                REAL,
                List.of(
                        new PurgeTarget("ops.a", "created_at < ?", Duration.ofDays(1)),
                        new PurgeTarget("ops.b", "created_at < ?", Duration.ofDays(1))),
                10,
                meters);
        StepContribution contribution = contribution();

        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc, times(2))
                .update(
                        eq("DELETE FROM ops.a WHERE ctid IN (SELECT ctid FROM ops.a WHERE created_at < ? LIMIT ?)"),
                        any(Object[].class));
        assertThat(contribution.getExitStatus().getExitDescription()).isEqualTo("Deleted rows; ops.a: 14; ops.b: 0");
        assertThat(meters.get("pti.retention.deleted")
                        .tag("table", "ops.a")
                        .counter()
                        .count())
                .isEqualTo(14.0);
    }

    @Test
    void metadataCleanupDeletesInstancesChildrenFirst() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(List.of(1L, 2L))
                .thenReturn(List.of(3L));
        BatchMetadataCleanupTasklet tasklet =
                new BatchMetadataCleanupTasklet(jdbc, REAL, Duration.ofDays(30), 2, new SimpleMeterRegistry());
        StepContribution contribution = contribution();

        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.CONTINUABLE);
        assertThat(tasklet.execute(contribution, null)).isEqualTo(RepeatStatus.FINISHED);

        verify(jdbc, times(6)).update(anyString(), eq("{1,2}"));
        verify(jdbc, times(6)).update(anyString(), eq("{3}"));
        assertThat(contribution.getExitStatus().getExitDescription())
                .startsWith("Deleted 3 job instance(s) older than 2026-08-30T21:20");
    }
}
