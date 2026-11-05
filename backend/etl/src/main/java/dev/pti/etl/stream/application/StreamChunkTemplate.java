package dev.pti.etl.stream.application;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.ErrorKind;
import dev.pti.common.error.ErrorPhase;
import dev.pti.common.error.FatalException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.application.MessageProcessor;
import dev.pti.etl.core.domain.InboundMessage;
import dev.pti.etl.core.domain.RuleContext;
import dev.pti.etl.core.domain.WriteSet;
import dev.pti.etl.fault.application.port.FaultInjector;
import dev.pti.etl.fault.domain.FaultPoint;
import dev.pti.etl.reference.application.ReferenceDataHolder;
import dev.pti.etl.stream.application.port.BatchCommittedPublisher;
import dev.pti.etl.stream.application.port.ChunkBatchLog;
import dev.pti.etl.stream.application.port.ChunkTracing;
import dev.pti.etl.stream.application.port.ChunkTransaction;
import dev.pti.etl.stream.application.port.StreamMetrics;
import dev.pti.etl.write.application.port.ChunkWriter;
import dev.pti.etl.write.application.port.DeadLetterWriter;
import dev.pti.etl.write.application.port.WriteMetrics;
import dev.pti.etl.write.domain.DeadLetter;
import dev.pti.etl.write.domain.RunMode;
import dev.pti.etl.write.domain.WriteContext;
import dev.pti.etl.write.domain.WriteMode;
import dev.pti.etl.write.domain.WriteOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The chunk algorithm of DOC-19 §5 for one poll (DOC-19 §6.2): process outside the transaction, write the batch in
 * one transaction, and on a data error while writing roll back and write again item by item behind savepoints, so
 * that exactly the bad messages go to the dead-letter queue. Data, dead letters and the {@code etl_stream_batch} row
 * commit together; the caller commits the offsets after this method returns (ADR-0004).
 *
 * <p>A plain application service (DOC-49 §3): the transaction, the batch log, the metrics, the commit event and the
 * tracing are outbound ports.
 */
public class StreamChunkTemplate {

    private static final Logger log = LoggerFactory.getLogger(StreamChunkTemplate.class);

    private final ChunkTransaction tx;
    private final DeadLetterWriter deadLetters;
    private final ChunkBatchLog batchLog;
    private final ErrorClassifier classifier;
    private final FaultInjector faults;
    private final BatchCommittedPublisher publisher;
    private final BusinessClock clock;
    private final ReferenceDataHolder reference;
    private final WriteMetrics stats;
    private final StreamMetrics metrics;
    private final ChunkTracing tracing;
    private final boolean failBatchOnDataError;

    public StreamChunkTemplate(
            ChunkTransaction tx,
            DeadLetterWriter deadLetters,
            ChunkBatchLog batchLog,
            ErrorClassifier classifier,
            FaultInjector faults,
            BatchCommittedPublisher publisher,
            BusinessClock clock,
            ReferenceDataHolder reference,
            WriteMetrics stats,
            StreamMetrics metrics,
            ChunkTracing tracing,
            boolean failBatchOnDataError) {
        this.tx = tx;
        this.deadLetters = deadLetters;
        this.batchLog = batchLog;
        this.classifier = classifier;
        this.faults = faults;
        this.publisher = publisher;
        this.clock = clock;
        this.reference = reference;
        this.stats = stats;
        this.metrics = metrics;
        this.tracing = tracing;
        this.failBatchOnDataError = failBatchOnDataError;
    }

    /**
     * Processes one poll as one chunk and returns once it has committed.
     *
     * @throws RuntimeException an infrastructure or fatal error, never a data error: the listener lets it reach the
     *     container error handler, so the offsets are not committed and the poll is delivered again
     */
    public StreamChunkResult execute(StreamChunkRequest request, MessageProcessor processor, ChunkWriter writer) {
        try (ChunkTracing.Poll poll = tracing.poll(request)) {
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
                result =
                        tx.inTransaction(savepoints -> writeBatch(request, writer, context, valid, skipped, startedAt));
            } catch (RuntimeException e) {
                if (classifier.classify(e, ErrorPhase.WRITE) != ErrorKind.DATA) {
                    throw e;
                }
                mode = WriteMode.SCAN;
                log.info(
                        "Chunk {} hit a data error while writing, scanning item by item: {}",
                        request.batchId(),
                        e.toString());
                metrics.scanRequested(request.source());
                result = tx.inTransaction(
                        savepoints -> scan(savepoints, request, writer, context, valid, skipped, startedAt));
            }
        } catch (RuntimeException e) {
            batchLog.insertFailedBestEffort(request, mode, e, startedAt, clock.realNow());
            metrics.batchCommitted(request.source(), StreamChunkResult.BatchStatus.FAILED);
            throw asInfrastructure(e);
        }
        if (result == null) {
            throw new FatalException("Transaction returned no result for chunk " + request.batchId());
        }
        metrics.chunkDuration(request.source(), mode, Duration.ofNanos(System.nanoTime() - start));
        recordLatency(request);
        metrics.batchCommitted(request.source(), result.status());

        faults.hit(FaultPoint.AFTER_COMMIT_BEFORE_ACK);
        publisher.publish(new MicroBatchCommitted(result, clock.realNow(), tracing.currentSpan()));
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
        tracing.commitSpan();
        faults.hit(FaultPoint.BEFORE_WRITE);
        WriteOutcome outcome =
                tracing.child("pti.etl.write", request.source().name(), () -> writer.write(valid, context));
        return finish(request, context, outcome, skipped, 0, WriteMode.BATCH, startedAt);
    }

    private StreamChunkResult scan(
            ChunkTransaction.Savepoints savepoints,
            StreamChunkRequest request,
            ChunkWriter writer,
            WriteContext context,
            List<WriteSet> valid,
            List<DeadLetter> skipped,
            Instant startedAt) {
        tracing.commitSpan();
        faults.hit(FaultPoint.BEFORE_WRITE);
        WriteOutcome outcome = WriteOutcome.none();
        int loadErrors = 0;
        for (WriteSet item : valid) {
            ChunkTransaction.Savepoint savepoint = savepoints.create();
            try {
                outcome = outcome.plus(writer.write(List.of(item), context));
                savepoint.release();
            } catch (RuntimeException e) {
                if (classifier.classify(e, ErrorPhase.WRITE) != ErrorKind.DATA) {
                    throw e;
                }
                savepoint.rollback();
                DeadLetter letter = deadLetters.loadFailure(item.origin(), e, item.businessKey(), request.batchId());
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
                outcome.routeIds(),
                outcome.written().stream()
                        .flatMap(set -> set.vehiclePositions().stream())
                        .toList());
        batchLog.insert(request, result, startedAt, clock.realNow());
        faults.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
        return result;
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
        Instant now = clock.realNow();
        for (InboundMessage m : request.messages()) {
            Instant recorded = m.recordTimestamp();
            if (recorded != null && !recorded.isAfter(now)) {
                metrics.commitLatency(request.source(), Duration.between(recorded, now));
            }
        }
    }
}
