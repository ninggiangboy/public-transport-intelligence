package dev.pti.api.system.adapter.in.scheduling;

import dev.pti.api.system.application.RefreshFreshness;

/**
 * Triggers the freshness probe (DOC-32 E-60): every {@code pti.observability.freshness-probe.interval} the scheduler
 * of {@code system.config} calls {@link #run()}. It holds no logic; the use case decides what to read and how a
 * failure is kept.
 */
public final class FreshnessProbeScheduler implements Runnable {

    private final RefreshFreshness refresh;

    public FreshnessProbeScheduler(RefreshFreshness refresh) {
        this.refresh = refresh;
    }

    @Override
    public void run() {
        refresh.execute();
    }
}
