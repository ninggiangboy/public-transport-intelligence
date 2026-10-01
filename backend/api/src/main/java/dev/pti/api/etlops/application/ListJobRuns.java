package dev.pti.api.etlops.application;

import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunFilter;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /etl/jobs} (DOC-32 E-30): the runs of a window, batch jobs and minutes of micro-batches, newest first. */
public final class ListJobRuns {

    private final JobRunReader runs;
    private final TransactionRunner tx;

    public ListJobRuns(JobRunReader runs, TransactionRunner tx) {
        this.runs = runs;
        this.tx = tx;
    }

    public Page<JobRun> execute(JobRunFilter filter, PageRequest page) {
        Page<JobRun> found = tx.inTransaction(() -> runs.list(filter, page));
        return new Page<>(found.items().stream().map(JobRun::withRestartability).toList(), found.next());
    }
}
