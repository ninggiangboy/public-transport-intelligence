package dev.pti.etl.gtfs;

import dev.pti.common.json.MessageJson;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.write.SqlResource;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.node.ObjectNode;

/**
 * Step {@code validate} (DOC-21 §4): gathers the structural issues of {@code fetch}, the row errors of the load steps
 * (GV-04) and the SQL checks on the STAGED version, writes the validation report, and exits {@code REJECTED} when
 * any error was found. Read-only apart from the report, so a restart simply runs it again.
 */
public class ValidateFeedTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ValidateFeedTasklet.class);

    static final Duration STATEMENT_TIMEOUT = Duration.ofSeconds(60);

    /** Tables counted for {@code row_counts}. */
    static final Map<String, String> COUNTED = countedTables();

    private final NamedParameterJdbcTemplate named;
    private final FeedVersions versions;
    private final BusinessClock clock;
    private final int maxSamples;
    private final Map<FeedCheck, String> checks = new EnumMap<>(FeedCheck.class);

    public ValidateFeedTasklet(
            NamedParameterJdbcTemplate named, FeedVersions versions, BusinessClock clock, int maxSamples) {
        this.named = named;
        this.versions = versions;
        this.clock = clock;
        this.maxSamples = maxSamples;
        for (FeedCheck check : FeedCheck.sqlChecks()) {
            checks.put(check, SqlResource.load("gtfs-validate/" + check.code()));
        }
    }

    private static Map<String, String> countedTables() {
        Map<String, String> tables = new LinkedHashMap<>();
        tables.put("agency", "dw.dim_agency");
        tables.put("routes", "dw.dim_route");
        tables.put("stops", "dw.dim_stop");
        tables.put("trips", "dw.gtfs_trip");
        tables.put("stop_times", "dw.gtfs_stop_time");
        tables.put("shapes", "dw.gtfs_shape");
        tables.put("calendar", "dw.gtfs_calendar");
        tables.put("calendar_dates", "dw.gtfs_calendar_date");
        return tables;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution step = contribution.getStepExecution();
        JobExecution job = step.getJobExecution();
        long version = FeedContext.feedVersionId(job);
        GtfsIssues issues =
                GtfsIssues.fromJson(job.getExecutionContext().getString(FeedContext.FETCH_ISSUES, ""), maxSamples);
        for (GtfsTable table : GtfsTable.LOAD_ORDER) {
            issues.addAll(GtfsIssues.fromJson(
                    job.getExecutionContext().getString(FeedContext.ROW_ISSUES_PREFIX + table.stepName(), ""),
                    maxSamples));
        }

        JdbcTemplate jdbc = named.getJdbcTemplate();
        jdbc.execute("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT.toMillis() + "ms'");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("feedVersionId", version)
                .addValue("businessToday", LocalDate.ofInstant(clock.instant(), FeedContext.agencyZone(job)))
                .addValue("activeFeedVersionId", versions.activeId().orElse(-1L));
        checks.forEach((check, sql) -> {
            List<ObjectNode> samples = new ArrayList<>();
            long[] total = {0};
            named.query(sql, params, rs -> {
                total[0] = rs.getLong("total");
                samples.add((ObjectNode) MessageJson.mapper().readTree(rs.getString("sample")));
            });
            issues.add(check, total[0], samples);
        });

        Map<String, Long> counts = new LinkedHashMap<>();
        COUNTED.forEach((name, table) -> counts.put(
                name,
                jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE feed_version_id = ?", Long.class, version)));

        String report = FeedReport.build(
                issues, counts, job.getExecutionContext().getString(FeedContext.EXTRA_COLUMNS, ""), durations(job));
        versions.setReport(version, report);
        for (FeedCheck check : issues.checks()) {
            if (!check.error()) {
                log.warn("Feed version {}: {} x{}", version, check.code(), issues.count(check));
            }
        }
        if (issues.hasErrors()) {
            List<String> failed = issues.checks().stream()
                    .filter(FeedCheck::error)
                    .map(FeedCheck::code)
                    .toList();
            log.error("Feed version {} rejected: {}", version, failed);
            contribution.setExitStatus(new ExitStatus(FetchFeedTasklet.REJECTED, "Failed checks " + failed));
        } else {
            contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription("Feed version " + version + " valid"));
        }
        return RepeatStatus.FINISHED;
    }

    /** Durations of the steps that ran in this execution: fetch, all loads together, and validate so far. */
    private static Map<String, Long> durations(JobExecution job) {
        Map<String, Long> durations = new LinkedHashMap<>();
        for (StepExecution s : job.getStepExecutions()) {
            if (s.getStartTime() == null || s.getEndTime() == null) {
                continue;
            }
            long ms = Duration.between(s.getStartTime(), s.getEndTime()).toMillis();
            String key = s.getStepName().startsWith("load") ? "load" : s.getStepName();
            durations.merge(key, ms, Long::sum);
        }
        return durations;
    }
}
