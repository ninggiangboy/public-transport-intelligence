package dev.pti.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.ApiIntegrationSupport;
import dev.pti.common.tx.TransactionRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The two datasources against the real grants (DOC-17, DOC-31 §10.1): {@code reader} is {@code api_reader} and can
 * write nothing, {@code operator} is {@code replay_operator} and can write what DOC-17 gives it and nothing in
 * {@code dw}; the two {@code TransactionRunner} beans carry their own isolation and read-only flag.
 */
class DataSourcesIT extends ApiIntegrationSupport {

    @Autowired
    @Qualifier("reader")
    private JdbcClient reader;

    @Autowired
    @Qualifier("operator")
    private JdbcClient operator;

    @Autowired
    @Qualifier("readerTx")
    private TransactionRunner readerTx;

    @Autowired
    @Qualifier("operatorTx")
    private TransactionRunner operatorTx;

    @AfterEach
    void clean() {
        asOwner(
                "DELETE FROM ops.runtime_flag WHERE key LIKE 'it.%'",
                "DELETE FROM ops.alert_event WHERE dedup_key LIKE 'it-%'");
    }

    private static final String INSERT_FLAG =
            "INSERT INTO ops.runtime_flag (key, value, description, updated_by) VALUES (?, '1', 'test flag', 'user:test')";

    private static void assertDenied(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> assertThat(rootMessage(e)).contains("permission denied"));
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    // ---------------------------------------------------------------------------------------------- reader

    @Test
    @DisplayName("reader connects as api_reader")
    void readerIsApiReader() {
        assertThat(reader.sql("SELECT current_user").query(String.class).single())
                .isEqualTo("api_reader");
    }

    @Test
    @DisplayName("reader can read the warehouse, the ops tables and the insight outputs")
    void readerReads() {
        assertThat(reader.sql("SELECT count(*) FROM dw.dim_route")
                        .query(Long.class)
                        .single())
                .isNotNull();
        assertThat(reader.sql("SELECT count(*) FROM ops.runtime_flag")
                        .query(Long.class)
                        .single())
                .isGreaterThan(0);
        assertThat(reader.sql("SELECT count(*) FROM insight.insight_otp_scorecard")
                        .query(Long.class)
                        .single())
                .isNotNull();
        assertThat(reader.sql("SELECT count(*) FROM ops.ops_job_run_v")
                        .query(Long.class)
                        .single())
                .isNotNull();
    }

    @Test
    @DisplayName("reader cannot write anywhere, nor read the analytics state or Spring Batch tables")
    void readerCannotWrite() {
        assertDenied(() -> reader.sql(INSERT_FLAG).param("it.reader-flag").update());
        assertDenied(() -> reader.sql("UPDATE ops.runtime_flag SET value = '2'").update());
        assertDenied(() -> reader.sql("DELETE FROM ops.alert_event").update());
        assertDenied(
                () -> reader.sql("UPDATE dw.dim_route SET display_name = 'x'").update());
        assertDenied(() -> reader.sql("INSERT INTO dw.gtfs_feed_version (feed_hash) VALUES ('x')")
                .update());
        assertDenied(() -> reader.sql("SELECT * FROM insight.analytics_bunching_pair_state")
                .query()
                .listOfRows());
        assertDenied(() ->
                reader.sql("SELECT * FROM batch.batch_job_instance").query().listOfRows());
    }

    @Test
    @DisplayName("readerTx runs read-only transactions")
    void readerTransactionIsReadOnly() {
        String readOnly = readerTx.inTransaction(() ->
                reader.sql("SHOW transaction_read_only").query(String.class).single());

        assertThat(readOnly).isEqualTo("on");
    }

    // -------------------------------------------------------------------------------------------- operator

    @Test
    @DisplayName("operator connects as replay_operator")
    void operatorIsReplayOperator() {
        assertThat(operator.sql("SELECT current_user").query(String.class).single())
                .isEqualTo("replay_operator");
    }

    @Test
    @DisplayName("operator writes what DOC-17 allows: flags, alerts, and the read-back of both")
    void operatorWritesWhatItMay() {
        operator.sql(INSERT_FLAG).param("it.operator-flag").update();
        int updated = operator.sql(
                        "UPDATE ops.runtime_flag SET value = '2', updated_by = 'user:test', updated_at = now()"
                                + " WHERE key = 'it.operator-flag'")
                .update();
        operator.sql("""
                        INSERT INTO ops.alert_event (id, type, severity, audience, title, dedup_key)
                        VALUES (gen_random_uuid(), 'INFRA', 1, 'ENGINEERING', 'IT alert', 'it-alert-1')""").update();
        int acknowledged = operator.sql("UPDATE ops.alert_event SET acknowledged_by = 'user:test',"
                        + " acknowledged_at = now() WHERE dedup_key = 'it-alert-1'")
                .update();

        assertThat(updated).isEqualTo(1);
        assertThat(acknowledged).isEqualTo(1);
        assertThat(operator.sql("SELECT value::text FROM ops.runtime_flag WHERE key = 'it.operator-flag'")
                        .query(String.class)
                        .single())
                .isEqualTo("2");
    }

    @Test
    @DisplayName("operator cannot touch dw, cannot delete, and cannot rewrite a column it was not given")
    void operatorCannotDoMore() {
        assertDenied(() -> operator.sql("SELECT * FROM dw.dim_route").query().listOfRows());
        assertDenied(
                () -> operator.sql("UPDATE dw.dim_route SET display_name = 'x'").update());
        assertDenied(() -> operator.sql("DELETE FROM ops.runtime_flag").update());
        assertDenied(() -> operator.sql("DELETE FROM ops.replay_request").update());
        assertDenied(() ->
                operator.sql("UPDATE ops.runtime_flag SET description = 'x'").update());
        assertDenied(
                () -> operator.sql("UPDATE ops.alert_event SET title = 'x'").update());
        assertDenied(() -> operator.sql("SELECT * FROM insight.analytics_bunching_pair_state")
                .query()
                .listOfRows());
    }

    @Test
    @DisplayName("operatorTx is READ COMMITTED and read-write; a failure rolls the whole transaction back")
    void operatorTransactionRollsBack() {
        String isolation = operatorTx.inTransaction(() ->
                operator.sql("SHOW transaction_isolation").query(String.class).single());
        String readOnly = operatorTx.inTransaction(() ->
                operator.sql("SHOW transaction_read_only").query(String.class).single());

        assertThat(isolation).isEqualTo("read committed");
        assertThat(readOnly).isEqualTo("off");

        assertThatThrownBy(() -> operatorTx.inTransaction(() -> {
                    operator.sql(INSERT_FLAG).param("it.rolled-back").update();
                    throw new IllegalStateException("fail after the write");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(operator.sql("SELECT count(*) FROM ops.runtime_flag WHERE key = 'it.rolled-back'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("Inside one operator transaction the written row is read back (no replica lag)")
    void operatorReadsBackInTheSameTransaction() {
        String value = operatorTx.inTransaction(() -> {
            operator.sql(INSERT_FLAG).param("it.read-back").update();
            return operator.sql("SELECT updated_by FROM ops.runtime_flag WHERE key = 'it.read-back'")
                    .query(String.class)
                    .single();
        });

        assertThat(value).isEqualTo("user:test");
    }
}
