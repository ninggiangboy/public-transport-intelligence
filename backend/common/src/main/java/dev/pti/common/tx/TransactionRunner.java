package dev.pti.common.tx;

import java.util.function.Supplier;

/**
 * The transaction boundary a use case chooses (DOC-49 §4.2, §5.1). It is a port in the shared kernel, so use cases
 * stay plain Java; {@code dev.pti.common.spring.SpringTransactionRunner} implements it with Spring's
 * {@code TransactionTemplate}. An exception thrown by the work rolls the transaction back and propagates unchanged.
 */
public interface TransactionRunner {

    /** Joins the current transaction or starts one (REQUIRED). */
    <T> T inTransaction(Supplier<T> work);

    /** Always starts a new transaction (REQUIRES_NEW), e.g. one route per transaction in DOC-23 §3. */
    <T> T inNewTransaction(Supplier<T> work);
}
