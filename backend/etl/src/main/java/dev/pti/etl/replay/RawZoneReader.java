package dev.pti.etl.replay;

import dev.pti.common.error.DeserializationException;
import dev.pti.common.error.FatalException;
import dev.pti.etl.batch.UnreadableRecordException;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.raw.RawZone;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

/**
 * Reads the raw zone records of a replay window (DOC-22 §4.4) hour by hour, object by object, line by line.
 *
 * <p>The object list is not stored: after {@code pti.replay.raw-settle} it no longer changes, so each hour is listed
 * again when the reader reaches it (DR-89 made the stored list too large). The step context keeps the hour, the
 * object within the hour and the lines consumed, so a restart continues at the last committed line. Offsets already
 * returned for the current {@code (hour, partition)} are skipped: the at-least-once sink may write one offset twice.
 */
public class RawZoneReader implements ItemStreamReader<InboundMessage> {

    private static final Logger log = LoggerFactory.getLogger(RawZoneReader.class);

    static final String HOUR = "pti.replay.hour";
    static final String OBJECT = "pti.replay.object";
    static final String LINE = "pti.replay.line";
    static final String PAIR = "pti.replay.pair";
    static final String LAST_OFFSET = "pti.replay.lastOffset";
    public static final String LINES_READ = "pti.replay.linesRead";
    public static final String FILTERED = "pti.replay.filtered";
    public static final String DUPLICATE = "pti.replay.duplicate";
    public static final String OBJECTS = "pti.replay.objectsRead";

    private final RawZone raw;

    private EtlSource source = EtlSource.GTFS_RT_VEHICLE_POSITION;
    private String topic = "";
    private ReplayRange range = new ReplayRange(Instant.EPOCH, Instant.EPOCH.plusMillis(1));
    private List<String> hours = List.of();
    private int hour;
    private int object;
    private long line;
    private String pair = "";
    private long lastOffset = -1;
    private long linesRead;
    private long filtered;
    private long duplicate;
    private long objectsRead;
    private @Nullable List<RawObjectKey> hourObjects;
    private @Nullable BufferedReader current;
    private @Nullable RawObjectKey currentKey;

    public RawZoneReader(RawZone raw) {
        this.raw = raw;
    }

    @Override
    public void open(ExecutionContext context) {
        var stepContext = StepSynchronizationManager.getContext();
        if (stepContext == null) {
            throw new IllegalStateException("RawZoneReader runs inside a step");
        }
        JobParameters parameters = stepContext.getStepExecution().getJobParameters();
        source = EtlSource.valueOf(required(parameters, "source"));
        topic = source.requireTopic();
        range = new ReplayRange(
                Instant.parse(required(parameters, "fromTs")), Instant.parse(required(parameters, "toTs")));
        hours = range.hourPrefixes(topic).stream().map(raw::key).toList();
        hour = context.getInt(HOUR, 0);
        object = context.getInt(OBJECT, 0);
        line = context.getLong(LINE, 0L);
        pair = context.getString(PAIR, "");
        lastOffset = context.getLong(LAST_OFFSET, -1L);
        linesRead = context.getLong(LINES_READ, 0L);
        filtered = context.getLong(FILTERED, 0L);
        duplicate = context.getLong(DUPLICATE, 0L);
        objectsRead = context.getLong(OBJECTS, 0L);
        hourObjects = null;
        current = null;
    }

    private static String required(JobParameters parameters, String name) {
        String value = parameters.getString(name);
        if (value == null || value.isBlank()) {
            throw new FatalException("Job parameter " + name + " is missing");
        }
        return value;
    }

    @Override
    public @Nullable InboundMessage read() {
        while (true) {
            if (current == null && !openNextObject()) {
                return null;
            }
            BufferedReader reader = current;
            RawObjectKey key = currentKey;
            if (reader == null || key == null) {
                return null;
            }
            String text;
            try {
                text = reader.readLine();
            } catch (IOException | UncheckedIOException e) {
                log.warn(
                        "Raw zone object {} is corrupt after {} line(s); skipping the rest: {}",
                        key.key(),
                        line,
                        e.toString());
                nextObject();
                throw new UnreadableRecordException(
                        new InboundMessage(
                                source,
                                null,
                                key.key().getBytes(StandardCharsets.UTF_8),
                                null,
                                null,
                                null,
                                null,
                                Map.of()),
                        new DeserializationException(
                                "Corrupt raw zone object " + key.key() + ": " + e.getMessage(), e));
            }
            if (text == null) {
                nextObject();
                continue;
            }
            line++;
            linesRead++;
            if (text.isBlank()) {
                continue;
            }
            InboundMessage message = RawLines.parse(source, topic, key.partition(), text);
            String messagePair = hour + "/" + key.partition();
            if (!messagePair.equals(pair)) {
                pair = messagePair;
                lastOffset = -1;
            }
            Long messageOffset = message.offset();
            long offset = messageOffset == null ? -1 : messageOffset;
            if (offset <= lastOffset) {
                duplicate++;
                continue;
            }
            lastOffset = offset;
            Instant recorded = message.recordTimestamp();
            if (recorded == null || !range.contains(recorded)) {
                filtered++;
                continue;
            }
            return message;
        }
    }

    /** Opens the object at the saved position, skipping the lines a previous run already consumed. */
    private boolean openNextObject() {
        while (hour < hours.size()) {
            List<RawObjectKey> objects = objectsOfHour();
            if (object < objects.size()) {
                RawObjectKey key = objects.get(object);
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(new GZIPInputStream(raw.open(key.key())), StandardCharsets.UTF_8));
                    long skipped = reader.lines().limit(line).count();
                    log.debug("Resuming {} after {} line(s)", key.key(), skipped);
                    current = reader;
                    currentKey = key;
                    return true;
                } catch (RawZone.RawObjectMissingException e) {
                    throw new FatalException("Raw zone object disappeared during the replay: " + key.key(), e);
                } catch (IOException e) {
                    log.warn("Raw zone object {} cannot be opened as gzip; skipping it: {}", key.key(), e.toString());
                    nextObject();
                    throw new UnreadableRecordException(
                            new InboundMessage(
                                    source,
                                    null,
                                    key.key().getBytes(StandardCharsets.UTF_8),
                                    null,
                                    null,
                                    null,
                                    null,
                                    Map.of()),
                            new DeserializationException("Corrupt raw zone object " + key.key() + ": " + e, e));
                }
            }
            hour++;
            object = 0;
            line = 0;
            hourObjects = null;
        }
        return false;
    }

    private List<RawObjectKey> objectsOfHour() {
        if (hourObjects == null) {
            hourObjects = ListObjectsTasklet.objects(raw, hours.get(hour), topic);
        }
        return hourObjects;
    }

    private void nextObject() {
        closeCurrent();
        object++;
        line = 0;
        objectsRead++;
    }

    private void closeCurrent() {
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("Closing a raw zone object failed: {}", e.toString());
            }
            current = null;
        }
    }

    @Override
    public void update(ExecutionContext context) {
        context.putInt(HOUR, hour);
        context.putInt(OBJECT, object);
        context.putLong(LINE, line);
        context.putString(PAIR, pair);
        context.putLong(LAST_OFFSET, lastOffset);
        context.putLong(LINES_READ, linesRead);
        context.putLong(FILTERED, filtered);
        context.putLong(DUPLICATE, duplicate);
        context.putLong(OBJECTS, objectsRead);
    }

    @Override
    public void close() {
        closeCurrent();
    }
}
