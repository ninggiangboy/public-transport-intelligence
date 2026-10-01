package dev.pti.api.etlops.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One run in full (DOC-32 E-32). A batch job carries {@code parameters}, {@code steps} and the {@code request} it came
 * from; a stream run carries its micro-batches.
 */
public record JobRunDetail(
        JobRun run,
        List<JobParameter> parameters,
        List<StepRun> steps,
        @Nullable RequestRef request,
        List<StreamBatch> batches) {

    public JobRunDetail {
        parameters = List.copyOf(parameters);
        steps = List.copyOf(steps);
        batches = List.copyOf(batches);
    }
}
