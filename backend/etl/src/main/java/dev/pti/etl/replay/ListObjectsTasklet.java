package dev.pti.etl.replay;

import dev.pti.common.error.FatalException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.raw.RawZone;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * Step {@code listObjects} (DOC-22 §4.3): counts the objects of the window and refuses a window with more than
 * {@code pti.replay.max-objects}. Only the count is kept; {@link RawZoneReader} lists each hour again when it gets
 * there, because the full list of a busy week no longer fits an execution context (DR-89).
 */
public class ListObjectsTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(ListObjectsTasklet.class);

    public static final String OBJECT_COUNT = "pti.replay.objects";

    private final RawZone raw;
    private final int maxObjects;

    public ListObjectsTasklet(RawZone raw, int maxObjects) {
        this.raw = raw;
        this.maxObjects = maxObjects;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters parameters = contribution.getStepExecution().getJobParameters();
        String topic = EtlSource.valueOf(String.valueOf(parameters.getString("source")))
                .requireTopic();
        ReplayRange range = new ReplayRange(
                Instant.parse(String.valueOf(parameters.getString("fromTs"))),
                Instant.parse(String.valueOf(parameters.getString("toTs"))));
        long count = 0;
        for (String hour : range.hourPrefixes(topic)) {
            count += objects(raw, raw.key(hour), topic).size();
            if (count > maxObjects) {
                throw new FatalException("The window holds more than " + maxObjects
                        + " raw objects (pti.replay.max-objects); split it into shorter replays");
            }
        }
        contribution.getStepExecution().getJobExecution().getExecutionContext().putLong(OBJECT_COUNT, count);
        contribution.setExitStatus(ExitStatus.COMPLETED.addExitDescription(
                count == 0 ? "No raw objects in range" : count + " raw objects in range"));
        return RepeatStatus.FINISHED;
    }

    /** The objects of one hour directory in reading order; names that do not match are logged and left out. */
    static List<RawObjectKey> objects(RawZone raw, String hourPrefix, String topic) {
        List<RawObjectKey> objects = new ArrayList<>();
        for (String key : raw.list(hourPrefix)) {
            Optional<RawObjectKey> parsed = RawObjectKey.parse(key, topic);
            if (parsed.isPresent()) {
                objects.add(parsed.get());
            } else {
                log.warn("Ignoring raw zone object with an unexpected name: {}", key);
            }
        }
        objects.sort(RawObjectKey.READING_ORDER);
        return objects;
    }
}
