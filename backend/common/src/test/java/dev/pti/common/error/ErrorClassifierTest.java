package dev.pti.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.dq.DlqStage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.MalformedInputException;
import java.sql.SQLException;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.kafka.KafkaException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.json.JsonMapper;

/** DOC-30 §6: E-01 to E-04. */
class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    /** The driver's own exception, as JdbcTemplate wraps it. */
    @Test
    void classifiesTheDriversException() {
        PSQLException e = new PSQLException("connection lost", PSQLState.CONNECTION_FAILURE);

        assertThat(classifier.classify(new CannotGetJdbcConnectionException("pool", e)))
                .isEqualTo(ErrorKind.TRANSIENT_INFRA);
        assertThat(classifier.classify(new DataIntegrityViolationException(
                        "check", new PSQLException("violates check", PSQLState.CHECK_VIOLATION))))
                .isEqualTo(ErrorKind.DATA);
    }

    static Stream<Arguments> sqlStates() {
        return Stream.of(
                Arguments.of("08000", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08001", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08003", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08004", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08006", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08007", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("08P01", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("40001", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("40P01", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("53000", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("53100", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("53200", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("53300", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("55P03", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("57014", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("57P01", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("57P02", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("57P03", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("58000", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("58030", ErrorKind.TRANSIENT_INFRA),
                Arguments.of("22001", ErrorKind.DATA),
                Arguments.of("22003", ErrorKind.DATA),
                Arguments.of("22007", ErrorKind.DATA),
                Arguments.of("22P02", ErrorKind.DATA),
                Arguments.of("23502", ErrorKind.DATA),
                Arguments.of("23503", ErrorKind.DATA),
                Arguments.of("23505", ErrorKind.DATA),
                Arguments.of("23514", ErrorKind.DATA),
                Arguments.of("23P01", ErrorKind.DATA),
                Arguments.of("21000", ErrorKind.FATAL),
                Arguments.of("25P02", ErrorKind.FATAL),
                Arguments.of("42P01", ErrorKind.FATAL),
                Arguments.of("42501", ErrorKind.FATAL),
                Arguments.of("0A000", ErrorKind.FATAL),
                Arguments.of("P0001", ErrorKind.DATA),
                Arguments.of("XX000", ErrorKind.FATAL));
    }

    @ParameterizedTest(name = "SQLState {0} -> {1}")
    @MethodSource("sqlStates")
    @DisplayName("E-01 SQLState table")
    void classifiesBySqlState(String state, ErrorKind expected) {
        SQLException sql = new SQLException("server said " + state, state);

        assertThat(classifier.classify(sql)).isEqualTo(expected);
        assertThat(classifier.classify(new UncategorizedSQLException("batch", "INSERT", sql)))
                .isEqualTo(expected);
        assertThat(classifier.classify(new KafkaException("wrapped", new UncategorizedSQLException("x", "y", sql))))
                .isEqualTo(expected);
    }

    static Stream<Arguments> exceptions() {
        SQLException connection = new SQLException("refused", "08001");
        return Stream.of(
                Arguments.of(new DeserializationException("bad", null), ErrorKind.DATA),
                Arguments.of(new SchemaViolationException("payload.vehicle_id: required"), ErrorKind.DATA),
                Arguments.of(
                        new RuleViolationException(DlqStage.QUALITY, "DQ-03", "Route 9 not found"), ErrorKind.DATA),
                Arguments.of(new TransientInfraException("down", null), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new FatalException("bug"), ErrorKind.FATAL),
                Arguments.of(new SkipLimitExceededException(10, new RuntimeException()), ErrorKind.FATAL),
                Arguments.of(new OptimisticLockingFailureException("stale version"), ErrorKind.FATAL),
                Arguments.of(
                        new CannotGetJdbcConnectionException("no connection", connection), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new CannotGetJdbcConnectionException("no connection"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new TransientDataAccessResourceException("pool"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new QueryTimeoutException("slow"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new RecoverableDataAccessException("recover"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new CannotAcquireLockException("lock"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new DataIntegrityViolationException("constraint"), ErrorKind.DATA),
                Arguments.of(new DuplicateKeyException("dup", new SQLException("dup", "23505")), ErrorKind.DATA),
                Arguments.of(new EmptyResultDataAccessException(1), ErrorKind.DATA),
                Arguments.of(
                        CallNotPermittedException.createCallNotPermittedException(
                                CircuitBreaker.ofDefaults("warehouse")),
                        ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new org.apache.kafka.common.errors.TimeoutException("kafka"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new NotLeaderOrFollowerException("leader moved"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(
                        new KafkaException("send", new org.apache.kafka.common.errors.TimeoutException("t")),
                        ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new KafkaException("config"), ErrorKind.FATAL),
                Arguments.of(SdkClientException.create("network"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(
                        S3Exception.builder()
                                .statusCode(503)
                                .message("slow down")
                                .build(),
                        ErrorKind.TRANSIENT_INFRA),
                Arguments.of(
                        S3Exception.builder()
                                .statusCode(429)
                                .message("throttled")
                                .build(),
                        ErrorKind.TRANSIENT_INFRA),
                Arguments.of(
                        NoSuchKeyException.builder()
                                .statusCode(404)
                                .message("gone")
                                .build(),
                        ErrorKind.FATAL),
                Arguments.of(
                        S3Exception.builder().statusCode(403).message("denied").build(), ErrorKind.FATAL),
                Arguments.of(new SocketTimeoutException("read timed out"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new ConnectException("refused"), ErrorKind.TRANSIENT_INFRA),
                Arguments.of(new NullPointerException(), ErrorKind.FATAL),
                Arguments.of(new IllegalStateException("bug"), ErrorKind.FATAL),
                Arguments.of(new ClassCastException(), ErrorKind.FATAL),
                Arguments.of(new IOException("disk"), ErrorKind.FATAL));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("exceptions")
    @DisplayName("E-01 exception table")
    void classifiesProjectAndLibraryExceptions(Throwable throwable, ErrorKind expected) {
        assertThat(classifier.classify(throwable, ErrorPhase.PROCESS)).isEqualTo(expected);
    }

    static Stream<Throwable> parseErrors() {
        JsonMapper mapper = JsonMapper.builder().build();
        StreamReadException jackson;
        try {
            mapper.readTree("{broken");
            throw new AssertionError("expected a parse error");
        } catch (StreamReadException e) {
            jackson = e;
        }
        return Stream.of(
                jackson,
                new MalformedInputException(1),
                new DateTimeParseException("bad", "x", 0),
                new NumberFormatException("NaN"),
                new jakarta.validation.ConstraintViolationException("invalid", java.util.Set.of()));
    }

    @ParameterizedTest
    @MethodSource("parseErrors")
    void parseErrorsAreDataWhileReading(Throwable parseError) {
        assertThat(classifier.classify(parseError, ErrorPhase.READ)).isEqualTo(ErrorKind.DATA);
        assertThat(classifier.classify(parseError, ErrorPhase.PROCESS)).isEqualTo(ErrorKind.DATA);
    }

    @ParameterizedTest
    @MethodSource("parseErrors")
    @DisplayName("E-02 parse errors while writing are fatal")
    void parseErrorsAreFatalWhileWriting(Throwable parseError) {
        assertThat(classifier.classify(parseError, ErrorPhase.WRITE)).isEqualTo(ErrorKind.FATAL);
        assertThat(classifier.classify(parseError)).isEqualTo(ErrorKind.FATAL);
    }

    @Test
    @DisplayName("E-03 a deep cause chain stops after ten levels")
    void deepCauseChainIsBounded() {
        Throwable chain = new SQLException("deep", "08006");
        for (int i = 0; i < 11; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertThat(classifier.classify(chain)).isEqualTo(ErrorKind.FATAL);
    }

    @Test
    void causeWithinTenLevelsIsFound() {
        Throwable chain = new SQLException("deep", "08006");
        for (int i = 0; i < 5; i++) {
            chain = new RuntimeException("level " + i, chain);
        }

        assertThat(classifier.classify(chain)).isEqualTo(ErrorKind.TRANSIENT_INFRA);
    }

    @Test
    void selfReferencingCauseDoesNotLoop() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(classifier.classify(b)).isEqualTo(ErrorKind.FATAL);
    }

    @Test
    @DisplayName("E-04 data exceptions carry no stack trace")
    void dataExceptionHasNoStackTrace() {
        DataException e = new RuleViolationException(DlqStage.QUALITY, "DQ-06", "Outside the service area");

        assertThat(e.getStackTrace()).isEmpty();
        assertThat(new SchemaViolationException("x").getStackTrace()).isEmpty();
    }

    @Test
    void errorClassIsTheRuleIdOrTheCauseName() {
        assertThat(new RuleViolationException(DlqStage.QUALITY, "DQ-03", "m").errorClass())
                .isEqualTo("DQ-03");
        assertThat(new SchemaViolationException("m").errorClass()).isEqualTo("SchemaViolation");
        assertThat(new DeserializationException("m", new MalformedInputException(1)).errorClass())
                .isEqualTo("MalformedInputException");
        assertThat(new DeserializationException("m", null).errorClass()).isEqualTo("DeserializationException");
    }
}
