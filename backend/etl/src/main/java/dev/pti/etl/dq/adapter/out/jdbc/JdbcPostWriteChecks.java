package dev.pti.etl.dq.adapter.out.jdbc;

import dev.pti.common.error.FatalException;
import dev.pti.etl.dq.application.port.DqCheckMetrics;
import dev.pti.etl.dq.application.port.PostWriteChecks;
import dev.pti.etl.dq.application.port.PostWriteRuleStore;
import dev.pti.etl.dq.domain.PostWriteRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link PostWriteChecks} on Postgres (DOC-16 §3): the rule's statement runs behind a savepoint with a local
 * statement timeout, and a rule that times out or fails is logged and counted without failing the job.
 */
public final class JdbcPostWriteChecks implements PostWriteChecks {

    private static final Logger log = LoggerFactory.getLogger(JdbcPostWriteChecks.class);

    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate savepoint;
    private final Duration statementTimeout;
    private final PostWriteRuleStore store;
    private final DqCheckMetrics metrics;
    private final Map<PostWriteRule, String> sql = new EnumMap<>(PostWriteRule.class);

    public JdbcPostWriteChecks(
            NamedParameterJdbcTemplate named,
            PlatformTransactionManager transactionManager,
            Duration statementTimeout,
            PostWriteRuleStore store,
            DqCheckMetrics metrics) {
        this.named = named;
        this.statementTimeout = statementTimeout;
        this.store = store;
        this.metrics = metrics;
        this.savepoint = new TransactionTemplate(transactionManager);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        for (PostWriteRule rule : PostWriteRule.values()) {
            sql.put(rule, load("dq/" + rule.id()));
        }
    }

    @Override
    public void run(PostWriteRule rule, Parameters parameters) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("now", java.time.OffsetDateTime.ofInstant(parameters.businessNow(), ZoneOffset.UTC))
                .addValue("realNow", java.time.OffsetDateTime.ofInstant(parameters.realNow(), ZoneOffset.UTC))
                .addValue("today", parameters.today())
                .addValue("graceSeconds", (double) parameters.refundGrace().toSeconds())
                .addValue("activeFeedVersionId", parameters.activeFeedVersionId());
        try {
            savepoint.executeWithoutResult(status -> {
                named.getJdbcTemplate()
                        .execute("SET LOCAL statement_timeout = '" + statementTimeout.toMillis() + "ms'");
                named.query(sql.get(rule), params, rs -> {
                    long violations = rs.getLong("violation_count");
                    long population = rs.getLong("population");
                    Long pop = rs.wasNull() ? null : population;
                    store.insert(rule, violations, pop, rs.getString("sample"));
                    if (rule.breached(violations, pop)) {
                        log.warn("{} on {}: {} violation(s)", rule.id(), rule.table(), violations);
                    }
                });
            });
        } catch (DataAccessException e) {
            log.warn(
                    "{} did not finish: {}", rule.id(), e.getMostSpecificCause().getMessage());
            metrics.checkFailed(rule.id());
        }
    }

    private static String load(String name) {
        String path = "sql/" + name + ".sql";
        try (InputStream in = JdbcPostWriteChecks.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing SQL resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FatalException("Cannot read SQL resource " + path, e);
        }
    }
}
