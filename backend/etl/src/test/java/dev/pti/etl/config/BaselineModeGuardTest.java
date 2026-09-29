package dev.pti.etl.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** S-15 (DOC-20 §14): a baseline switch outside the experiment profile stops the application at startup. */
class BaselineModeGuardTest {

    private static EtlProperties etl(EtlProperties.DedupMode dedup) {
        return new EtlProperties(
                new EtlProperties.Consumer(500, Duration.ofSeconds(1), 65536),
                new EtlProperties.Retry(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), 5, Duration.ofSeconds(60)),
                new EtlProperties.Reference(Duration.ofSeconds(30), 3),
                new EtlProperties.Health(Duration.ofSeconds(30), URI.create("http://connect:8083")),
                new EtlProperties.KnownKeyCache(20000),
                new EtlProperties.Batch(500, 0.2, 100),
                new EtlProperties.Dedup(true, Duration.ofHours(1)),
                new EtlProperties.Baseline(
                        EtlProperties.OffsetCommit.MANUAL,
                        EtlProperties.ErrorMode.SKIP,
                        EtlProperties.WriteMode.UPSERT,
                        dedup));
    }

    @Test
    void theDefaultsStartEverywhere() {
        assertThatCode(() -> new BaselineModeGuard(etl(EtlProperties.DedupMode.ON), new MockEnvironment()))
                .doesNotThrowAnyException();
    }

    @Test
    void aBaselineSwitchNeedsTheExperimentProfile() {
        assertThatThrownBy(() -> new BaselineModeGuard(etl(EtlProperties.DedupMode.OFF), new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("experiment profile");
        MockEnvironment experiment = new MockEnvironment();
        experiment.setActiveProfiles("stream", "experiment");
        assertThatCode(() -> new BaselineModeGuard(etl(EtlProperties.DedupMode.OFF), experiment))
                .doesNotThrowAnyException();
    }
}
