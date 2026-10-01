package dev.pti.api.etlops.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The id of a run (DOC-15 §5): {@code job:<jobExecutionId>} or {@code stream:<listenerId>:<yyyy-MM-ddTHH:mmZ>}. Text
 * of any other shape is not a run id, which for a caller is the same as a run that does not exist (DOC-31 §9).
 */
public record RunId(
        RunKind kind,
        @Nullable Long jobExecutionId,
        @Nullable String listenerId,
        @Nullable Instant minute) {

    private static final Pattern JOB = Pattern.compile("^job:(\\d{1,18})$");
    private static final Pattern STREAM =
            Pattern.compile("^stream:([a-z0-9-]+):(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2})Z$");

    public static Optional<RunId> parse(String text) {
        Matcher job = JOB.matcher(text);
        if (job.matches()) {
            return Optional.of(job(Long.parseLong(job.group(1))));
        }
        Matcher stream = STREAM.matcher(text);
        if (stream.matches()) {
            try {
                Instant minute = LocalDateTime.parse(stream.group(2)).toInstant(ZoneOffset.UTC);
                return Optional.of(new RunId(RunKind.STREAM, null, stream.group(1), minute));
            } catch (DateTimeParseException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public static RunId job(long jobExecutionId) {
        return new RunId(RunKind.BATCH_JOB, jobExecutionId, null, null);
    }

    /** The text form, as it is in {@code ops_job_run_v.run_id}. */
    public String text() {
        if (kind == RunKind.BATCH_JOB) {
            return "job:" + jobExecutionId;
        }
        return "stream:" + listenerId + ":" + minute.toString().substring(0, 16) + "Z";
    }
}
