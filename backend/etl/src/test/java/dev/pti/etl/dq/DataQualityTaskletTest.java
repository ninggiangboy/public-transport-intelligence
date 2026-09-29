package dev.pti.etl.dq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class DataQualityTaskletTest {

    private static final Instant NOW = Instant.parse("2026-09-29T21:20:00Z");

    @Test
    void thresholds() {
        assertThat(PostWriteRule.DQ_20.breached(1, null)).isTrue();
        assertThat(PostWriteRule.DQ_20.breached(0, null)).isFalse();
        assertThat(PostWriteRule.DQ_23.breached(1, 10_000L)).isFalse();
        assertThat(PostWriteRule.DQ_23.breached(11, 10_000L)).isTrue();
        assertThat(PostWriteRule.DQ_23.breached(5, null)).isFalse();
        assertThat(PostWriteRule.DQ_26.breached(99, 100L)).isFalse();
        assertThat(PostWriteRule.DQ_25.id()).isEqualTo("DQ-25");
        assertThat(PostWriteRule.DQ_26.interval()).isEqualTo(Duration.ofDays(1));
        assertThat(PostWriteRule.DQ_21.table()).isEqualTo("dw.fact_*_default");
    }

    @Test
    void everyDueEnabledRuleRunsOncePerJob() {
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        DqCheckResults results = mock(DqCheckResults.class);
        when(results.lastRun(any())).thenReturn(Optional.empty());
        when(results.lastRun(PostWriteRule.DQ_26)).thenReturn(Optional.of(NOW.minusSeconds(3600)));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        DataQualityTasklet tasklet = new DataQualityTasklet(
                named,
                results,
                mock(PlatformTransactionManager.class),
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                ZoneId.of("America/Chicago"),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                id -> !id.equals("DQ-24"),
                () -> -1L,
                meters);
        StepContribution contribution = new StepContribution(MetaDataInstanceFactory.createStepExecution());
        tasklet = spyRun(tasklet);

        int calls = 0;
        while (tasklet.execute(contribution, null) == RepeatStatus.CONTINUABLE) {
            calls++;
        }

        assertThat(calls).isEqualTo(PostWriteRule.values().length);
        assertThat(contribution.getExitStatus().getExitDescription())
                .isEqualTo("Ran DQ-20, DQ-21, DQ-22, DQ-23, DQ-25");
        org.mockito.Mockito.verify(results, never())
                .insert(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    /** The real run needs a database; here every rule "runs" without touching one. */
    private static DataQualityTasklet spyRun(DataQualityTasklet tasklet) {
        DataQualityTasklet spy = org.mockito.Mockito.spy(tasklet);
        org.mockito.Mockito.doNothing().when(spy).run(any());
        return spy;
    }
}
