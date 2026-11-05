package dev.pti.etl.gtfs.adapter.in.batch;

import dev.pti.etl.gtfs.application.GtfsIssues;
import dev.pti.etl.gtfs.application.port.FeedReports;
import dev.pti.etl.gtfs.domain.FeedCheck;
import dev.pti.etl.gtfs.domain.GtfsInsert;
import dev.pti.etl.gtfs.domain.GtfsRow;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;

/**
 * Collects the row errors of a load step for the validation report instead of the dead-letter queue: a feed is
 * accepted or rejected as a whole (DOC-21 §3.2). Counts live in the step context, so a restart keeps them, and move
 * to the job context when the step ends.
 */
public class GtfsSkipCollector implements SkipListener<GtfsRow, GtfsInsert>, StepExecutionListener {

    static final String STEP_KEY = "pti.gtfs.rowIssues";

    private final int maxSamples;
    private final FeedReports reports;
    private final Supplier<StepExecution> step;

    public GtfsSkipCollector(int maxSamples, FeedReports reports) {
        this(maxSamples, reports, GtfsSkipCollector::current);
    }

    GtfsSkipCollector(int maxSamples, FeedReports reports, Supplier<StepExecution> step) {
        this.maxSamples = maxSamples;
        this.reports = reports;
        this.step = step;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        Map<String, Object> sample = GtfsIssues.sample();
        if (t instanceof FlatFileParseException parse) {
            sample.put("line", parse.getLineNumber());
        }
        sample.put("message", message(t));
        record(sample);
    }

    @Override
    public void onSkipInProcess(GtfsRow row, Throwable t) {
        record(sample(row.file(), row.line(), message(t)));
    }

    @Override
    public void onSkipInWrite(GtfsInsert insert, Throwable t) {
        record(sample(insert.file(), insert.line(), message(t)));
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        String issues = stepExecution.getExecutionContext().getString(STEP_KEY, "");
        if (!issues.isEmpty()) {
            stepExecution
                    .getJobExecution()
                    .getExecutionContext()
                    .putString(FeedContext.ROW_ISSUES_PREFIX + stepExecution.getStepName(), issues);
        }
        return stepExecution.getExitStatus();
    }

    private void record(Map<String, Object> sample) {
        StepExecution execution = step.get();
        GtfsIssues issues =
                reports.issuesFromJson(execution.getExecutionContext().getString(STEP_KEY, ""), maxSamples);
        issues.add(FeedCheck.GV_04, sample);
        execution.getExecutionContext().putString(STEP_KEY, reports.issuesToJson(issues));
    }

    private static Map<String, Object> sample(String file, int line, String message) {
        Map<String, Object> sample = GtfsIssues.sample();
        sample.put("file", file);
        sample.put("line", line);
        sample.put("message", message);
        return sample;
    }

    /** The database message without the SQL around it, e.g. {@code duplicate key value violates …}. */
    static String message(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = String.valueOf(root.getMessage()).strip();
        int newline = message.indexOf('\n');
        String first = newline < 0 ? message : message.substring(0, newline);
        return first.startsWith("ERROR: ") ? first.substring("ERROR: ".length()) : first;
    }

    /** The step execution of the calling thread; only set while Spring Batch runs a step. */
    private static StepExecution current() {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("No step is running on this thread");
        }
        return context.getStepExecution();
    }
}
