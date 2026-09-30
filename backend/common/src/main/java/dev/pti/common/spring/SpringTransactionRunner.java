package dev.pti.common.spring;

import dev.pti.common.tx.TransactionRunner;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionRunner} on Spring's {@link TransactionTemplate} (DOC-49 §4.2, §11.2). Each app declares one bean
 * per datasource in its {@code config}: {@code new SpringTransactionRunner(manager).readOnly()} for the reader,
 * {@code new SpringTransactionRunner(manager).isolation(Isolation.READ_COMMITTED)} for the operator.
 *
 * <p>The runner is immutable; {@link #readOnly()} and {@link #isolation(Isolation)} return a configured copy. A
 * runner that joins an existing transaction keeps that transaction's read-only flag and isolation, as Spring does.
 */
public final class SpringTransactionRunner implements TransactionRunner {

    private final PlatformTransactionManager manager;
    private final boolean readOnly;
    private final Isolation isolation;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;

    /** A read-write runner with the datasource's default isolation. */
    public SpringTransactionRunner(PlatformTransactionManager manager) {
        this(manager, false, Isolation.DEFAULT);
    }

    private SpringTransactionRunner(PlatformTransactionManager manager, boolean readOnly, Isolation isolation) {
        this.manager = manager;
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.required = template(manager, TransactionDefinition.PROPAGATION_REQUIRED, readOnly, isolation);
        this.requiresNew = template(manager, TransactionDefinition.PROPAGATION_REQUIRES_NEW, readOnly, isolation);
    }

    /** A copy whose transactions are read-only (the {@code reader} datasource, DOC-31 §10.1). */
    public SpringTransactionRunner readOnly() {
        return new SpringTransactionRunner(manager, true, isolation);
    }

    /** A copy whose transactions use the given isolation level (the {@code operator} datasource: READ_COMMITTED). */
    public SpringTransactionRunner isolation(Isolation level) {
        return new SpringTransactionRunner(manager, readOnly, level);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return run(required, work);
    }

    @Override
    public <T> T inNewTransaction(Supplier<T> work) {
        return run(requiresNew, work);
    }

    private static <T> T run(TransactionTemplate template, Supplier<T> work) {
        // The template commits when the callback returns and rolls back and rethrows when it throws. The work may
        // return null, which the template passes through.
        return template.execute(status -> work.get());
    }

    private static TransactionTemplate template(
            PlatformTransactionManager manager, int propagation, boolean readOnly, Isolation isolation) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(propagation);
        template.setReadOnly(readOnly);
        template.setIsolationLevel(isolation.value());
        return template;
    }
}
