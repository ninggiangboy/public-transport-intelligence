package dev.pti.analytics.core.adapter.out.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.pti.analytics.WarehouseSupport;
import dev.pti.analytics.core.domain.LockNames;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** The advisory lock and the transaction limits of DOC-23 §2.5 and §12.1 against a real PostgreSQL. */
class JdbcAdvisoryLockIT {

    private final WarehouseSupport db = new WarehouseSupport();
    private final JdbcAdvisoryLock lock = new JdbcAdvisoryLock(db.jdbc);
    private final JdbcTransactionLimits limits = new JdbcTransactionLimits(db.jdbc);
    private final ExecutorService holder = Executors.newSingleThreadExecutor();
    private final String name = LockNames.bunching("an1-" + UUID.randomUUID());

    @AfterEach
    void stop() {
        holder.shutdownNow();
    }

    /** Holds the lock in a transaction on another thread until {@code release} is counted down. */
    private Future<Boolean> holdLock(CountDownLatch holding, CountDownLatch release) {
        return holder.submit(() -> db.tx.inNewTransaction(() -> {
            boolean acquired = lock.tryAcquire(name);
            holding.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return acquired;
        }));
    }

    @Test
    void theFirstTransactionGetsTheLockAndAnotherOneDoesNot() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> first = holdLock(holding, release);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        boolean second = db.tx.inNewTransaction(() -> lock.tryAcquire(name));

        assertThat(second).as("held elsewhere: the live path skips the run").isFalse();
        release.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void theLockEndsWithTheTransaction() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> first = holdLock(holding, release);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        first.get(30, TimeUnit.SECONDS);

        assertThat(db.tx.inNewTransaction(() -> lock.tryAcquire(name))).isTrue();
    }

    @Test
    void theSameTransactionMayAskAgain() {
        boolean both = db.tx.inNewTransaction(() -> lock.tryAcquire(name) && lock.tryAcquire(name));

        assertThat(both).isTrue();
    }

    @Test
    void differentNamesDoNotBlockEachOther() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> first = holdLock(holding, release);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        boolean other = db.tx.inNewTransaction(() -> lock.tryAcquire(LockNames.bunching("an1-" + UUID.randomUUID())));

        assertThat(other).isTrue();
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
    }

    @Test
    void waitingForTheLockGivesUpWhenTheLockTimeoutRunsOut() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Boolean> first = holdLock(holding, release);
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> db.tx.inNewTransaction(() -> {
                    lock.acquire(name, Duration.ofMillis(300));
                    return null;
                }));
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
    }

    @Test
    void waitingForAFreeLockReturnsAtOnce() {
        db.tx.inNewTransaction(() -> {
            lock.acquire(name, Duration.ofSeconds(60));
            return null;
        });
    }

    @Test
    void aLockOutsideATransactionIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> lock.tryAcquire(name));
        assertThatIllegalStateException().isThrownBy(() -> lock.acquire(name, Duration.ofSeconds(1)));
    }

    @Test
    void aStatementTimeoutStopsALongStatementAndEndsWithTheTransaction() {
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> db.tx.inNewTransaction(() -> {
                    limits.statementTimeout(Duration.ofMillis(200));
                    return db.jdbc.sql("SELECT pg_sleep(5)").query().listOfRows();
                }));

        String after = db.tx.inNewTransaction(
                () -> db.jdbc.sql("SHOW statement_timeout").query(String.class).single());
        assertThat(after).isEqualTo("0");
    }

    @Test
    void aStatementTimeoutOutsideATransactionIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> limits.statementTimeout(Duration.ofSeconds(1)));
    }
}
