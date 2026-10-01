-- E-32: the parameters of a job execution.
SELECT name, type, value, identifying
FROM ops.ops_job_execution_param_v
WHERE job_execution_id = :jobExecutionId
ORDER BY name
