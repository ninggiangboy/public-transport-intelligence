package dev.pti.common.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.tx.TransactionRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class SpringTransactionRunnerTest {

    private RecordingTransactionManager manager;

    @BeforeEach
    void setUp() {
        manager = new RecordingTransactionManager();
    }

    @Test
    @DisplayName("inTransaction starts a transaction, commits it and returns the result")
    void inTransactionCommitsAndReturnsTheResult() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        String result = runner.inTransaction(() -> {
            manager.events.add("work");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(manager.events).containsExactly("begin", "work", "commit");
    }

    @Test
    void workMayReturnNull() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        String result = runner.inTransaction(() -> null);

        assertThat(result).isNull();
        assertThat(manager.events).containsExactly("begin", "commit");
    }

    @Test
    @DisplayName("inTransaction joins the transaction that is already running")
    void inTransactionJoinsTheCurrentTransaction() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        runner.inTransaction(() -> runner.inTransaction(() -> manager.events.add("inner")));

        assertThat(manager.events).containsExactly("begin", "inner", "commit");
    }

    @Test
    @DisplayName("inNewTransaction suspends the running transaction and commits its own")
    void inNewTransactionStartsANewTransaction() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        runner.inTransaction(() -> runner.inNewTransaction(() -> manager.events.add("inner")));

        assertThat(manager.events).containsExactly("begin", "suspend", "begin", "inner", "commit", "resume", "commit");
    }

    @Test
    void inNewTransactionWithoutARunningTransactionJustStartsOne() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        runner.inNewTransaction(() -> manager.events.add("work"));

        assertThat(manager.events).containsExactly("begin", "work", "commit");
    }

    @Test
    @DisplayName("An exception rolls the transaction back and propagates unchanged")
    void exceptionRollsBackAndPropagates() {
        TransactionRunner runner = new SpringTransactionRunner(manager);
        IllegalStateException failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> runner.inTransaction(() -> {
                    throw failure;
                }))
                .isSameAs(failure);

        assertThat(manager.events).containsExactly("begin", "rollback");
    }

    @Test
    void failureInANewTransactionRollsBackOnlyTheInnerOne() {
        TransactionRunner runner = new SpringTransactionRunner(manager);

        runner.inTransaction(() -> {
            assertThatThrownBy(() -> runner.inNewTransaction(() -> {
                        throw new IllegalStateException("inner");
                    }))
                    .hasMessage("inner");
            return null;
        });

        assertThat(manager.events).containsExactly("begin", "suspend", "begin", "rollback", "resume", "commit");
    }

    @Test
    @DisplayName("A runner is read-write with the default isolation until configured")
    void defaultRunnerIsReadWriteWithDefaultIsolation() {
        new SpringTransactionRunner(manager).inTransaction(() -> null);

        assertThat(manager.definitions).singleElement().satisfies(definition -> {
            assertThat(definition.isReadOnly()).isFalse();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        });
    }

    @Test
    @DisplayName("readOnly() gives read-only transactions for both propagations (the reader datasource)")
    void readOnlyRunnerOpensReadOnlyTransactions() {
        TransactionRunner reader = new SpringTransactionRunner(manager).readOnly();

        reader.inTransaction(() -> null);
        reader.inNewTransaction(() -> null);

        assertThat(manager.definitions)
                .hasSize(2)
                .allSatisfy(definition -> assertThat(definition.isReadOnly()).isTrue());
    }

    @Test
    @DisplayName("isolation(READ_COMMITTED) is applied to both propagations (the operator datasource)")
    void isolationIsAppliedToBothPropagations() {
        TransactionRunner operator = new SpringTransactionRunner(manager).isolation(Isolation.READ_COMMITTED);

        operator.inTransaction(() -> null);
        operator.inNewTransaction(() -> null);

        assertThat(manager.definitions).hasSize(2).allSatisfy(definition -> {
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(definition.isReadOnly()).isFalse();
        });
        assertThat(manager.definitions.get(0).getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThat(manager.definitions.get(1).getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void configuringACopyDoesNotChangeTheOriginal() {
        SpringTransactionRunner original = new SpringTransactionRunner(manager);
        original.readOnly().isolation(Isolation.SERIALIZABLE);

        original.inTransaction(() -> null);

        assertThat(manager.definitions).singleElement().satisfies(definition -> {
            assertThat(definition.isReadOnly()).isFalse();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_DEFAULT);
        });
    }

    @Test
    void readOnlyAndIsolationCombine() {
        new SpringTransactionRunner(manager)
                .readOnly()
                .isolation(Isolation.REPEATABLE_READ)
                .inTransaction(() -> null);
        new SpringTransactionRunner(manager)
                .isolation(Isolation.REPEATABLE_READ)
                .readOnly()
                .inTransaction(() -> null);

        assertThat(manager.definitions).hasSize(2).allSatisfy(definition -> {
            assertThat(definition.isReadOnly()).isTrue();
            assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        });
    }

    /** A transaction manager that only records what Spring asks of it; one transaction is active at a time. */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        final List<String> events = new ArrayList<>();
        final List<TransactionDefinition> definitions = new ArrayList<>();
        private final Object transaction = new Object();
        private boolean active;

        @Override
        protected Object doGetTransaction() {
            return transaction;
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            active = true;
            events.add("begin");
            definitions.add(definition);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            active = false;
            events.add("suspend");
            return "suspended";
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            active = true;
            events.add("resume");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            active = false;
        }
    }
}
