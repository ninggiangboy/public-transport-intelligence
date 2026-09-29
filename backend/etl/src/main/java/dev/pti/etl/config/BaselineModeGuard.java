package dev.pti.etl.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * DR-27, test S-15: the baseline switches {@code pti.etl.baseline.*} weaken the guarantees on purpose, so they may
 * only change in the {@code experiment} profile. Anywhere else the bean fails, and with it the application start.
 */
public final class BaselineModeGuard {

    private final EtlProperties.Baseline baseline;

    public BaselineModeGuard(EtlProperties etl, Environment env) {
        if (!etl.baseline().isDefault() && !env.acceptsProfiles(Profiles.of("experiment"))) {
            throw new IllegalStateException("pti.etl.baseline.* may only differ from its defaults in the experiment"
                    + " profile (DR-27): " + etl.baseline());
        }
        this.baseline = etl.baseline();
    }

    /** The baseline settings this pod runs with. */
    public EtlProperties.Baseline baseline() {
        return baseline;
    }
}
