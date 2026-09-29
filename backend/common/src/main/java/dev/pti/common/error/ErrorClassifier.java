package dev.pti.common.error;

import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.nio.charset.CharacterCodingException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.format.DateTimeParseException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Sorts any exception into {@link ErrorKind} (ADR-0006, DOC-30 §2). The cause chain is walked from the outside in,
 * at most {@value #MAX_DEPTH} levels, and the first rule that matches at any level decides; nothing matching means
 * {@code FATAL}.
 *
 * <p>Library exceptions (Spring, Kafka, AWS, Resilience4j) are recognised by class name, so {@code common} does not
 * depend on those libraries and every app classifies the same way.
 */
public final class ErrorClassifier {

    static final int MAX_DEPTH = 10;

    private static final Set<String> FATAL_BATCH = Set.of(
            "org.springframework.batch.core.step.skip.SkipLimitExceededException",
            "org.springframework.dao.OptimisticLockingFailureException");

    private static final Set<String> TRANSIENT_DATA_ACCESS = Set.of(
            "org.springframework.jdbc.CannotGetJdbcConnectionException",
            "org.springframework.dao.TransientDataAccessResourceException",
            "org.springframework.dao.QueryTimeoutException",
            "org.springframework.dao.RecoverableDataAccessException",
            "org.springframework.dao.CannotAcquireLockException",
            "org.springframework.dao.PessimisticLockingFailureException",
            // The connection died under the transaction: rollback or commit failed, or begin found no connection.
            "org.springframework.transaction.TransactionSystemException",
            "org.springframework.transaction.CannotCreateTransactionException");

    private static final Set<String> DATA_ACCESS_DATA = Set.of(
            "org.springframework.dao.DataIntegrityViolationException",
            "org.springframework.dao.EmptyResultDataAccessException");

    private static final Set<String> TRANSIENT_MISC = Set.of(
            "io.github.resilience4j.circuitbreaker.CallNotPermittedException",
            "org.apache.kafka.common.errors.RetriableException",
            "org.apache.kafka.common.errors.TimeoutException",
            "software.amazon.awssdk.core.exception.SdkClientException");

    private static final Set<String> PARSE_ERRORS = Set.of(
            "tools.jackson.core.JacksonException",
            "com.fasterxml.jackson.core.JacksonException",
            "jakarta.validation.ConstraintViolationException");

    private static final String DATA_ACCESS_EXCEPTION = "org.springframework.dao.DataAccessException";
    private static final String SDK_SERVICE_EXCEPTION = "software.amazon.awssdk.core.exception.SdkServiceException";

    /** Classifies an exception raised outside of reading or processing input. */
    public ErrorKind classify(Throwable throwable) {
        return classify(throwable, ErrorPhase.OTHER);
    }

    public ErrorKind classify(Throwable throwable, ErrorPhase phase) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < MAX_DEPTH && seen.put(current, Boolean.TRUE) == null; depth++) {
            ErrorKind kind = match(current, phase);
            if (kind != null) {
                return kind;
            }
            current = current.getCause();
        }
        return ErrorKind.FATAL;
    }

    private static @Nullable ErrorKind match(Throwable t, ErrorPhase phase) {
        if (t instanceof DataException) {
            return ErrorKind.DATA;
        }
        if (t instanceof TransientInfraException) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        if (t instanceof FatalException) {
            return ErrorKind.FATAL;
        }
        if (isAny(t, FATAL_BATCH)) {
            return ErrorKind.FATAL;
        }
        SQLException sql = t instanceof SQLException e ? e : (isA(t, DATA_ACCESS_EXCEPTION) ? sqlCause(t) : null);
        if (sql != null && sql.getSQLState() != null) {
            return bySqlState(sql.getSQLState());
        }
        if (isAny(t, TRANSIENT_DATA_ACCESS)) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        if (isAny(t, DATA_ACCESS_DATA)) {
            return ErrorKind.DATA;
        }
        if (isAny(t, TRANSIENT_MISC)) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        if (isA(t, SDK_SERVICE_EXCEPTION)) {
            int status = statusCode(t);
            return status >= 500 || status == 429 ? ErrorKind.TRANSIENT_INFRA : ErrorKind.FATAL;
        }
        if (isAny(t, PARSE_ERRORS)
                || t instanceof CharacterCodingException
                || t instanceof DateTimeParseException
                || t instanceof NumberFormatException) {
            return phase == ErrorPhase.READ || phase == ErrorPhase.PROCESS ? ErrorKind.DATA : ErrorKind.FATAL;
        }
        if (t instanceof SQLTransientException
                || t instanceof SQLRecoverableException
                || t instanceof SQLNonTransientConnectionException) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        if (t instanceof ConnectException || t instanceof InterruptedIOException) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        return null;
    }

    /** PostgreSQL SQLState classes (DOC-30 §2.3). */
    static ErrorKind bySqlState(String state) {
        if (state.startsWith("08")
                || state.equals("40001")
                || state.equals("40P01")
                || state.startsWith("53")
                || state.equals("55P03")
                || state.equals("57014")
                || state.startsWith("57P")
                || state.equals("58000")
                || state.equals("58030")) {
            return ErrorKind.TRANSIENT_INFRA;
        }
        if (state.startsWith("22") || state.startsWith("23") || state.equals("P0001")) {
            return ErrorKind.DATA;
        }
        return ErrorKind.FATAL;
    }

    private static @Nullable SQLException sqlCause(Throwable t) {
        for (Throwable c = t.getCause(); c != null && c != t; c = c.getCause()) {
            if (c instanceof SQLException e) {
                return e;
            }
        }
        return null;
    }

    private static int statusCode(Throwable t) {
        try {
            Method method = t.getClass().getMethod("statusCode");
            return (Integer) method.invoke(t);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 0;
        }
    }

    private static boolean isAny(Throwable t, Set<String> names) {
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            if (names.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isA(Throwable t, String name) {
        return isAny(t, Set.of(name));
    }
}
