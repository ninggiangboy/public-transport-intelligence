package dev.pti.etl.batch.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One launch of a job (DOC-19 §2): the value of its identifying parameter and the extra, non-identifying parameters.
 * The adapter turns it into Spring's {@code JobParameters}, so callers of other features never build Spring types.
 */
public record JobLaunch(String identity, Map<String, String> parameters) {

    /** Non-identifying parameter linking an execution to the {@code ops.job_request} that started it. */
    public static final String JOB_REQUEST_ID = "jobRequestId";

    public JobLaunch {
        parameters = Map.copyOf(parameters);
    }

    public JobLaunch with(String name, String value) {
        Map<String, String> all = new LinkedHashMap<>(parameters);
        all.put(name, value);
        return new JobLaunch(identity, all);
    }
}
