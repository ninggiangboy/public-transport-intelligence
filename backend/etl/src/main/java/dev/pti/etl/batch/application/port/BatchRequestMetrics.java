package dev.pti.etl.batch.application.port;

/** {@code pti_batch_job_requests_total{kind,outcome}} (DOC-19 §7.3): what happened to every handled job request. */
public interface BatchRequestMetrics {

    void requestHandled(JobRequestStore.Kind kind, String outcome);
}
