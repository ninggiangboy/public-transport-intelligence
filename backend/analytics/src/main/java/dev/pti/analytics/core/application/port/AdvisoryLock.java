package dev.pti.analytics.core.application.port;

import java.time.Duration;

/**
 * Transaction-level advisory lock of one unit of work (DOC-23 §2.5). The lock belongs to the current transaction and
 * is released when it ends, so both methods must run inside one. Names come from
 * {@link dev.pti.analytics.core.domain.LockNames}.
 */
public interface AdvisoryLock {

    /**
     * Tries to take the lock without waiting: the live path. {@code false} means another unit holds it; the caller
     * skips the run ({@code outcome="skipped_locked"}) and the next trigger continues from the cursor.
     */
    boolean tryAcquire(String lockName);

    /**
     * Waits for the lock: the job and recompute path.
     *
     * @param lockTimeout how long to wait ({@code SET LOCAL lock_timeout}); when it runs out the statement fails and
     *     the transaction is rolled back
     */
    void acquire(String lockName, Duration lockTimeout);
}
