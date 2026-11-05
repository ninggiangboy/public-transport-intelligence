package dev.pti.etl.maintenance.adapter.out.jdbc;

import dev.pti.etl.maintenance.application.port.BatchMetadataStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link BatchMetadataStore} on the Spring Batch schema (DR-62, DOC-18 §5): an instance goes only when its newest
 * execution ended {@code COMPLETED} or {@code ABANDONED} and none of its executions is recent. Tables are cleared
 * children first; {@code ops.etl_batch_step} follows its step executions by cascade.
 */
public final class JdbcBatchMetadataStore implements BatchMetadataStore {

    private static final String SELECT_INSTANCES = """
            SELECT ji.job_instance_id
            FROM batch.batch_job_instance ji
            WHERE NOT EXISTS (SELECT 1 FROM batch.batch_job_execution je
                              WHERE je.job_instance_id = ji.job_instance_id AND je.create_time >= ?)
              AND (SELECT je.status FROM batch.batch_job_execution je
                   WHERE je.job_instance_id = ji.job_instance_id
                   ORDER BY je.job_execution_id DESC LIMIT 1) IN ('COMPLETED', 'ABANDONED')
            ORDER BY ji.job_instance_id
            LIMIT ?
            """;

    private static final String EXECUTIONS =
            "SELECT job_execution_id FROM batch.batch_job_execution WHERE job_instance_id = ANY (?::bigint[])";

    private static final List<String> DELETES = List.of(
            "DELETE FROM batch.batch_step_execution_context WHERE step_execution_id IN "
                    + "(SELECT step_execution_id FROM batch.batch_step_execution WHERE job_execution_id IN ("
                    + EXECUTIONS + "))",
            "DELETE FROM batch.batch_step_execution WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution_context WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution_params WHERE job_execution_id IN (" + EXECUTIONS + ")",
            "DELETE FROM batch.batch_job_execution WHERE job_instance_id = ANY (?::bigint[])",
            "DELETE FROM batch.batch_job_instance WHERE job_instance_id = ANY (?::bigint[])");

    private final JdbcTemplate jdbc;

    public JdbcBatchMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int deleteBatch(LocalDateTime cutoff, int limit) {
        List<Long> instances = jdbc.queryForList(SELECT_INSTANCES, Long.class, cutoff, limit);
        if (instances.isEmpty()) {
            return 0;
        }
        String ids = instances.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        for (String delete : DELETES) {
            jdbc.update(delete, ids);
        }
        return instances.size();
    }
}
