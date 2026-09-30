package dev.pti.etl.stream;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import dev.pti.common.error.ErrorPhase;
import dev.pti.common.error.FatalException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.fault.FaultInjector;
import dev.pti.etl.fault.FaultPoint;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.DeadLetter;
import dev.pti.etl.write.DeadLetterWriter;
import dev.pti.etl.write.RunMode;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteMode;
import dev.pti.etl.write.WriteOutcome;
import dev.pti.etl.write.WriteStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The chunk algorithm of DOC-19 §5 for one poll (DOC-19 §6.2): process outside the transaction, write the batch in
 * one transaction, and on a data error while writing roll back and write again item by item behind savepoints, so
 * that exactly the bad messages go to the dead-letter queue. Data, dead letters and the {@code etl_stream_batch} row
 * commit together; the caller commits the offsets after this method returns (ADR-0004).
 */
public class StreamChunkTemplate {

    private static final Logger log = LoggerFactory.getLogger(StreamChunkTemplate.class);

    private final TransactionTemplate tx;
    private final DeadLetterWriter deadLetters;
    private final StreamBatchLog batchLog;
    private final ErrorClassifier classifier;
    private final FaultInjector faults;
    private final ApplicationEventPublisher events;
    private final BusinessClock clock;
    private final ReferenceDataHolder reference;
    private final WriteStats stats;
    private final MeterRegistry meters;
    private final boolean failBatchOnDataError;
    private final PollTracing tracing;

    public StreamChunkTemplate(
            TransactionTemplate tx,
            DeadLetterWriter deadLetters,
            StreamBatchLog batchLog,
            ErrorClassifier classifier,
            FaultInjector faults,
            ApplicationEventPublisher events,
            BusinessClock clock,
            ReferenceDataHolder reference,
            WriteStats stats,
            MeterRegistry meters,
            boolean failBatchOnDataError) {
        this(
                tx,
                deadLetters,
                batchLog,
                classifier,
                faults,
                events,
                clock,
                reference,
                stats,
                meters,
                failBatchOnDataError,
                PollTracing.NOOP);
    }

    /** @param tracing the spans of DOC-28 §5.2 */
    public StreamChunkTemplate(
            TransactionTemplate tx,
            DeadLetterWriter deadLetters,
            StreamBatchLog batchLog,
            ErrorClassifier classifier,
            FaultInjector faults,
            ApplicationEventPublisher events,
            BusinessClock clock,
            ReferenceDataHolder reference,
            WriteStats stats,
            MeterRegistry meters,
            boolean failBatchOnDataError,
            PollTracing tracing) {
        this.tx = tx;
        this.deadLetters = deadLetters;
        this.batchLog = batchLog;
        this.classifier = classifier;
        this.faults = faults;
        this.events = events;
        this.clock = clock;
        this.reference = reference;
        this.stats = stats;
        this.meters = meters;
        this.failBatchOnDataError = failBatchOnDataError;
        this.tracing = tracing;
    }

    /**
     * Processes one poll as one chunk and returns once it has committed.
     *
     * @throws RuntimeException an infrastructure or fatal error, never a data error: the listener lets it reach the
     *     container error handler, so the offsets are not committed and the poll is delivered again
     */
    public StreamChunkResult execute(StreamChunkRequest request, MessageProcessor processor, ChunkWriter writer) {
        try (PollTracing.Poll poll = tracing.poll(request)) {
            try {
                StreamChunkResult result = executeInSpan(request, processor, writer);
                poll.outcome(result.status().name().toLowerCase(Locale.ROOT));
                return result;
            } catch (RuntimeException e) {
                poll.error(e);
                throw e;
            }
        }
    }

    private StreamChunkResult executeInSpan(
            StreamChunkRequest request, MessageProcessor processor, ChunkWriter writer) {
        Instant startedAt = clock.realNow();
        faults.hit(FaultPoint.BEFORE_PROCESS);
        RuleContext rules = new RuleContext(
                clock.instant(),
                clock.offset(),
                request.replay(),
                reference.current().orElse(null));
        List<WriteSet> valid = new ArrayList<>(request.messages().size());
        List<DeadLetter> skipped = new ArrayList<>();
        String source = request.source().name();
        tracing.child("pti.etl.process", source, () -> {
            for (InboundMessage message : request.messages()) {
                try {
                    valid.add(processor.process(message, rules));
                } catch (RuntimeException e) {
                    skipped.add(skip(message, e, processor, request));
                }
            }
        });
        faults.hit(FaultPoint.AFTER_PROCESS);

        WriteContext context =
                new WriteContext(request.batchId(), RunMode.STREAM, request.replay(), rules.businessNow());
        WriteMode mode = WriteMode.BATCH;
        long start = System.nanoTime();
        StreamChunkResult result;
        try {
            try {
                result = tx.execute(status -> writeBatch(request, writer, context, valid, skipped, startedAt));
            } catch (RuntimeException e) {
                if (classifier.classify(e, ErrorPhase.WRITE) != ErrorKind.DATA) {
                    throw e;
                }
                mode = WriteMode.SCAN;
                log.info(
                        "Chunk {} hit a data error while writing, scanning item by item: {}",
                        request.batchId(),
                        e.toString());
                scanCounter(request).increment();
                result = tx.execute(status -> scan(status, request, writer, context, valid, skipped, startedAt));
            }
        } catch (RuntimeException e) {
            batchLog.insertFailedBestEffort(request, mode, e, startedAt, clock.realNow());
            failedCounter(request).increment();
            throw asInfrastructure(e);
        }
        if (result == null) {
            throw new FatalException("Transaction returned no result for chunk " + request.batchId());
        }
        chunkTimer(request, mode).record(Duration.ofNanos(System.nanoTime() - start));
        recordLatency(request);
        Counter.builder("pti.etl.stream.batches")
                .tag("source", request.source().name())
                .tag("status", result.status().name())
                .register(meters)
                .increment();

        faults.hit(FaultPoint.AFTER_COMMIT_BEFORE_ACK);
        events.publishEvent(new MicroBatchCommitted(result));
        if (result.skipped() > 0 || mode == WriteMode.SCAN) {
            log.info(
                    "Chunk committed: read={} written={} skipped={} duplicate={} mode={}",
                    result.read(),
                    result.written(),
                    result.skipped(),
                    result.duplicate(),
                    mode);
        } else {
            log.debug(
                    "Chunk committed: read={} written={} duplicate={}",
                    result.read(),
                    result.written(),
                    result.duplicate());
        }
        return result;
    }

    private DeadLetter skip(
            InboundMessage message, RuntimeException error, MessageProcessor processor, StreamChunkRequest request) {
        DataException data = asDataException(error);
        if (failBatchOnDataError) {
            throw new DataBatchFailedException("Baseline fail-batch: " + data.getMessage(), data);
        }
        return DeadLetter.of(message, data, safeKey(processor, message), request.batchId());
    }

    /** A processor error is data only when the classifier says so; anything else fails the poll. */
    private DataException asDataException(RuntimeException error) {
        if (error instanceof DataException data) {
            return data;
        }
        if (classifier.classify(error, ErrorPhase.PROCESS) == ErrorKind.DATA) {
            return new DataException(DlqStage.DESERIALIZE, null, String.valueOf(error.getMessage()), error);
        }
        throw error;
    }

    private static @Nullable String safeKey(MessageProcessor processor, InboundMessage message) {
        try {
            return processor.businessKey(message);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private StreamChunkResult writeBatch(
            StreamChunkRequest request,
            ChunkWriter writer,
            WriteContext context,
            List<WriteSet> valid,
            List<DeadLetter> skipped,
            Instant startedAt) {
        traceCommit();
        faults.hit(FaultPoint.BEFORE_WRITE);
        WriteOutcome outcome =
                tracing.child("pti.etl.write", request.source().name(), () -> writer.write(valid, context));
        return finish(request, context, outcome, skipped, 0, WriteMode.BATCH, startedAt);
    }

    private StreamChunkResult scan(
            TransactionStatus status,
            StreamChunkRequest request,
            ChunkWriter writer,
            WriteContext context,
            List<WriteSet> valid,
            List<DeadLetter> skipped,
            Instant startedAt) {
        traceCommit();
        faults.hit(FaultPoint.BEFORE_WRITE);
        WriteOutcome outcome = WriteOutcome.none();
        int loadErrors = 0;
        for (WriteSet item : valid) {
            Object savepoint = status.createSavepoint();
            try {
                outcome = outcome.plus(writer.write(List.of(item), context));
                status.releaseSavepoint(savepoint);
            } catch (RuntimeException e) {
                if (classifier.classify(e, ErrorPhase.WRITE) != ErrorKind.DATA) {
                    throw e;
                }
                status.rollbackToSavepoint(savepoint);
                DeadLetter letter = DeadLetter.load(item.origin(), e, item.businessKey(), request.batchId());
                writeDeadLetter(letter, context);
                loadErrors++;
            }
        }
        return finish(request, context, outcome, skipped, loadErrors, WriteMode.SCAN, startedAt);
    }

    private StreamChunkResult finish(
            StreamChunkRequest request,
            WriteContext context,
            WriteOutcome outcome,
            List<DeadLetter> skipped,
            int loadErrors,
            WriteMode mode,
            Instant startedAt) {
        if (!skipped.isEmpty()) {
            tracing.child("pti.etl.dlq.write", request.source().name(), () -> {
                for (DeadLetter letter : skipped) {
                    writeDeadLetter(letter, context);
                }
            });
        }
        stats.recordSkipped(request.source(), RunMode.STREAM, skipped.size() + loadErrors);
        StreamChunkResult result = new StreamChunkResult(
                request.batchId(),
                request.source(),
                mode,
                request.messages().size(),
                outcome.writtenCount(),
                skipped.size() + loadErrors + outcome.rejected(),
                outcome.duplicate(),
                outcome.minEventTimestamp().orElse(null),
                outcome.maxEventTimestamp().orElse(null),
                request.minRecordTimestamp().orElse(null),
                outcome.routeIds());
        batchLog.insert(request, result, startedAt, clock.realNow());
        faults.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
        return result;
    }

    /** {@code pti.etl.commit}: from just before the commit until the transaction has completed. */
    private void traceCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            private @Nullable Span span;

            @Override
            public void beforeCommit(boolean readOnly) {
                span = tracing.startChild("pti.etl.commit");
            }

            @Override
            public void afterCompletion(int status) {
                if (span != null) {
                    if (status != STATUS_COMMITTED) {
                        span.tag("outcome", "rolled_back");
                    }
                    span.end();
                }
            }
        });
    }

    private void writeDeadLetter(DeadLetter letter, WriteContext context) {
        if (context.replay()) {
            deadLetters.writeReplay(letter);
        } else {
            deadLetters.write(letter);
        }
    }

    /** A data error that escapes the scan is not about one record: treat it as a bug, not as bad data. */
    private RuntimeException asInfrastructure(RuntimeException e) {
        if (e instanceof DataBatchFailedException) {
            return e;
        }
        if (classifier.classify(e, ErrorPhase.WRITE) == ErrorKind.DATA) {
            return new FatalException("Data error outside of any single record: " + e.getMessage(), e);
        }
        return e;
    }

    private void recordLatency(StreamChunkRequest request) {
        Timer timer = Timer.builder("pti.etl.kafka.to.commit")
                .tag("source", request.source().name())
                .register(meters);
        Instant now = clock.realNow();
        for (InboundMessage m : request.messages()) {
            Instant recorded = m.recordTimestamp();
            if (recorded != null && !recorded.isAfter(now)) {
                timer.record(Duration.between(recorded, now));
            }
        }
    }

    private Timer chunkTimer(StreamChunkRequest request, WriteMode mode) {
        return Timer.builder("pti.etl.chunk.duration")
                .tag("source", request.source().name())
                .tag("mode", RunMode.STREAM.tag())
                .tag("write_mode", mode.name().toLowerCase(Locale.ROOT))
                .register(meters);
    }

    private Counter scanCounter(StreamChunkRequest request) {
        return Counter.builder("pti.etl.chunk.scan")
                .tag("source", request.source().name())
                .tag("mode", RunMode.STREAM.tag())
                .register(meters);
    }

    private Counter failedCounter(StreamChunkRequest request) {
        return Counter.builder("pti.etl.stream.batches")
                .tag("source", request.source().name())
                .tag("status", StreamChunkResult.BatchStatus.FAILED.name())
                .register(meters);
    }
}
