package dev.pti.etl.maintenance.adapter.in.batch;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.maintenance.application.MaintainPartitions;
import dev.pti.etl.maintenance.application.ManagedTable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Creates the partitions of the coming days and drops those past retention (DOC-18 §5, DOC-14 §7.4). Dates are
 * business dates of the agency, so the job follows {@code PTI_CLOCK_OFFSET} (test L-01). Both functions are
 * idempotent, so a restart simply runs again.
 */
public class PartitionMaintenanceTasklet implements Tasklet {

    private final MaintainPartitions maintenance;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final List<ManagedTable> tables;

    public PartitionMaintenanceTasklet(
            MaintainPartitions maintenance, BusinessClock clock, ZoneId agencyZone, List<ManagedTable> tables) {
        this.maintenance = maintenance;
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.tables = List.copyOf(tables);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), agencyZone);
        List<String> summary = new ArrayList<>();
        for (ManagedTable t : tables) {
            MaintainPartitions.Result result = maintenance.maintain(t, today);
            summary.add(t.table() + ": created " + result.created() + ", dropped " + result.dropped());
        }
        contribution.setExitStatus(
                ExitStatus.COMPLETED.addExitDescription("Business date " + today + "; " + String.join("; ", summary)));
        return RepeatStatus.FINISHED;
    }
}
