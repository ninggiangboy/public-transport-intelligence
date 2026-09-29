package dev.pti.etl.gtfs;

import dev.pti.etl.batch.StepValues;
import java.util.function.Supplier;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.SkipListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import tools.jackson.databind.node.ObjectNode;

/**
 * Collects the row errors of a load step for the validation report instead of the dead-letter queue: a feed is
 * accepted or rejected as a whole (DOC-21 §3.2). Counts live in the step context, so a restart keeps them, and move
 * to the job context when the step ends.
 */
public class GtfsSkipCollector implements SkipListener<GtfsRow, GtfsInsert>, StepExecutionListener {

    static final String STEP_KEY = "pti.gtfs.rowIssues";

    private final int maxSamples;
    private final Supplier<StepExecution> step;

    public GtfsSkipCollector(int maxSamples) {
        this(maxSamples, StepValues::current);
    }

    GtfsSkipCollector(int maxSamples, Supplier<StepExecution> step) {
        this.maxSamples = maxSamples;
        this.step = step;
    }

    @Override
    public void onSkipInRead(Throwable t) {
        ObjectNode sample = GtfsIssues.sample();
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

    private void record(ObjectNode sample) {
        StepExecution execution = step.get();
        GtfsIssues issues = GtfsIssues.fromJson(execution.getExecutionContext().getString(STEP_KEY, ""), maxSamples);
        issues.add(FeedCheck.GV_04, sample);
        execution.getExecutionContext().putString(STEP_KEY, issues.toJson());
    }

    private static ObjectNode sample(String file, int line, String message) {
        ObjectNode sample = GtfsIssues.sample();
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
}
