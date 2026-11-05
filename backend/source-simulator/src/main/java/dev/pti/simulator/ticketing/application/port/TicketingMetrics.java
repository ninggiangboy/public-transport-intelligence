package dev.pti.simulator.ticketing.application.port;

/** The ticketing counters (DOC-28 §3.1). */
public interface TicketingMetrics {

    /** {@code pti_sim_ticket_transactions_total{txn_type,action}}: one sale, refund, void or delete. */
    void transaction(String txnType, String action);

    /** {@code pti_sim_ticketing_errors_total}: a tick whose writes failed and were dropped. */
    void error();
}
