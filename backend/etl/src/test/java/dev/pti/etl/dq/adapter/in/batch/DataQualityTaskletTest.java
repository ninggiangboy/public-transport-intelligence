package dev.pti.etl.dq.adapter.in.batch;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.dq.application.RunDataQualityChecks;
import dev.pti.etl.dq.application.port.DqCheckMetrics;
import dev.pti.etl.dq.application.port.PostWriteChecks;
import dev.pti.etl.dq.application.port.PostWriteRuleStore;
import dev.pti.etl.dq.domain.PostWriteRule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

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
        List<PostWriteRule> ran = new ArrayList<>();
        PostWriteRuleStore store = new PostWriteRuleStore() {
            @Override
            public Optional<Instant> lastRun(PostWriteRule rule) {
                return rule == PostWriteRule.DQ_26 ? Optional.of(NOW.minusSeconds(3600)) : Optional.empty();
            }

            @Override
            public void insert(PostWriteRule rule, long violations, @Nullable Long population, String sample) {}
        };
        PostWriteChecks checks = (rule, parameters) -> ran.add(rule);
        DqCheckMetrics metrics = ruleId -> {};
        RunDataQualityChecks useCase = new RunDataQualityChecks(
                store,
                checks,
                new BusinessClock(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO),
                ZoneId.of("America/Chicago"),
                Duration.ofMinutes(5),
                id -> !id.equals("DQ-24"),
                () -> -1L);
        DataQualityTasklet tasklet = new DataQualityTasklet(useCase);
        StepContribution contribution = new StepContribution(MetaDataInstanceFactory.createStepExecution());

        int calls = 0;
        while (tasklet.execute(contribution, null) == RepeatStatus.CONTINUABLE) {
            calls++;
        }

        assertThat(calls).isEqualTo(PostWriteRule.values().length);
        assertThat(contribution.getExitStatus().getExitDescription())
                .isEqualTo("Ran DQ-20, DQ-21, DQ-22, DQ-23, DQ-25");
        assertThat(ran)
                .containsExactly(
                        PostWriteRule.DQ_20,
                        PostWriteRule.DQ_21,
                        PostWriteRule.DQ_22,
                        PostWriteRule.DQ_23,
                        PostWriteRule.DQ_25);
    }
}
