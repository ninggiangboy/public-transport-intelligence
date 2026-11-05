package dev.pti.simulator.emit.application.port;

/** The resend-queue meters (DOC-28 §3.1). */
public interface ResendMetrics {

    /** {@code pti.sim.emissions.skipped{reason=resend_queue_full}}: resends dropped because the queue was full. */
    void skipped();

    /** {@code pti.sim.resend.queue.depth}: resends waiting to go out. */
    void depth(int depth);
}
