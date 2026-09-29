package dev.pti.etl.dq;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.write.SqlResource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Step {@code runDueRules} of {@code DataQualityJob} (DOC-16 §3): every rule whose interval has passed since its last
 * result runs, one rule per call ({@code CONTINUABLE}) so no transaction is long. A rule runs behind a savepoint: a
 * rule that times out or fails is logged and counted in {@code pti_dq_check_errors_total}, and the job goes on.
 */
public class DataQualityTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DataQualityTasklet.class);

    static final String NEXT = "pti.dq.nextRule";
    static final String RAN = "pti.dq.ran";

    /** A rule is due slightly before its interval ends, so a 5-minute rule is not skipped by schedule jitter. */
    static final Duration SLACK = Duration.ofSeconds(30);

    private final NamedParameterJdbcTemplate named;
    private final DqCheckResults results;
    private final TransactionTemplate savepoint;
    private final BusinessClock clock;
    private final ZoneId agencyZone;
    private final Duration refundGrace;
    private final Duration statementTimeout;
    private final Predicate<String> enabled;
    private final LongSupplier activeFeedVersion;
    private final MeterRegistry meters;
    private final Map<PostWriteRule, String> sql = new EnumMap<>(PostWriteRule.class);

    public DataQualityTasklet(
            NamedParameterJdbcTemplate named,
            DqCheckResults results,
            PlatformTransactionManager transactionManager,
            BusinessClock clock,
            ZoneId agencyZone,
            Duration refundGrace,
            Duration statementTimeout,
            Predicate<String> enabled,
            LongSupplier activeFeedVersion,
            MeterRegistry meters) {
        this.named = named;
        this.results = results;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.clock = clock;
        this.agencyZone = agencyZone;
        this.refundGrace = refundGrace;
        this.statementTimeout = statementTimeout;
        this.enabled = enabled;
        this.activeFeedVersion = activeFeedVersion;
        this.meters = meters;
        for (PostWriteRule rule : PostWriteRule.values()) {
            sql.put(rule, SqlResource.load("dq/" + rule.id()));
        }
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        List<PostWriteRule> rules = List.of(PostWriteRule.values());
        int index = context.getInt(NEXT, 0);
        if (index >= rules.size()) {
            contribution.setExitStatus(
                    ExitStatus.COMPLETED.addExitDescription("Ran " + context.getString(RAN, "no rule")));
            return RepeatStatus.FINISHED;
        }
        context.putInt(NEXT, index + 1);
        PostWriteRule rule = rules.get(index);
        if (enabled.test(rule.id()) && isDue(rule)) {
            run(rule);
            String ran = context.getString(RAN, "");
            context.putString(RAN, ran.isEmpty() ? rule.id() : ran + ", " + rule.id());
        }
        return RepeatStatus.CONTINUABLE;
    }

    private boolean isDue(PostWriteRule rule) {
        Instant now = clock.realNow();
        return results.lastRun(rule)
                .map(last -> !last.plus(rule.interval()).minus(SLACK).isAfter(now))
                .orElse(true);
    }

    void run(PostWriteRule rule) {
        Instant businessNow = clock.instant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", OffsetDateTime.ofInstant(businessNow, ZoneOffset.UTC))
                .addValue("realNow", OffsetDateTime.ofInstant(clock.realNow(), ZoneOffset.UTC))
                .addValue("today", LocalDate.ofInstant(businessNow, agencyZone))
                .addValue("graceSeconds", (double) refundGrace.toSeconds())
                .addValue("activeFeedVersionId", activeFeedVersion.getAsLong());
        try {
            savepoint.executeWithoutResult(status -> {
                named.getJdbcTemplate()
                        .execute("SET LOCAL statement_timeout = '" + statementTimeout.toMillis() + "ms'");
                named.query(sql.get(rule), params, rs -> {
                    long violations = rs.getLong("violation_count");
                    long population = rs.getLong("population");
                    Long pop = rs.wasNull() ? null : population;
                    results.insert(rule, violations, pop, rs.getString("sample"));
                    if (rule.breached(violations, pop)) {
                        log.warn("{} on {}: {} violation(s)", rule.id(), rule.table(), violations);
                    }
                });
            });
        } catch (DataAccessException e) {
            log.warn(
                    "{} did not finish: {}", rule.id(), e.getMostSpecificCause().getMessage());
            Counter.builder("pti.dq.check.errors")
                    .tag("rule", rule.id())
                    .register(meters)
                    .increment();
        }
    }
}
