package dev.pti.etl.dq.application.port;

/** {@code pti_dq_check_errors_total} (DOC-16 §3): a rule whose run did not finish. */
public interface DqCheckMetrics {

    void checkFailed(String ruleId);
}
