package dev.pti.etl.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;
import dev.pti.common.error.DeserializationException;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.TransientInfraException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.fault.FaultInjector;
import dev.pti.etl.fault.FaultPoint;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.rules.RuleContext;
import dev.pti.etl.testing.EtlFixtures;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.DeadLetter;
import dev.pti.etl.write.DeadLetterWriter;
import dev.pti.etl.write.WriteContext;
import dev.pti.etl.write.WriteOutcome;
import dev.pti.etl.write.WriteStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;

class StepComponentsTest {

    private static final UUID BATCH_ID = UUID.fromString("01923d6e-0000-7000-8000-000000000001");

    private static StepExecution step(boolean replay) {
        StepExecution step = MetaDataInstanceFactory.createStepExecution(new JobParametersBuilder()
                .addString(StepValues.REPLAY, String.valueOf(replay), false)
                .toJobParameters());
        step.getExecutionContext().putString(StepValues.BATCH_ID, BATCH_ID.toString());
        return step;
    }

    private static final BusinessClock CLOCK =
            new BusinessClock(Clock.fixed(EtlFixtures.NOW, ZoneOffset.UTC), Duration.ZERO);

    @Test
    void stepValuesReadTheBatchIdAndReplayFlag() {
        StepExecution step = step(true);
        assertThat(StepValues.batchId(step)).isEqualTo(BATCH_ID);
        assertThat(StepValues.replay(step)).isTrue();
        assertThat(StepValues.replay(MetaDataInstanceFactory.createStepExecution()))
                .isFalse();
        StepValues.addRejected(step, 0);
        assertThat(StepValues.rejected(step)).isZero();
        StepValues.addRejected(step, 2);
        StepValues.addRejected(step, 3);
        assertThat(StepValues.rejected(step)).isEqualTo(5);
    }

    @Test
    void stepValuesNeedABatchIdAndARunningStep() {
        assertThatThrownBy(() -> StepValues.batchId(MetaDataInstanceFactory.createStepExecution()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BatchIdStepListener");
        assertThatThrownBy(StepValues::current).isInstanceOf(IllegalStateException.class);
        StepExecution step = step(false);
        StepSynchronizationManager.register(step);
        try {
            assertThat(StepValues.current()).isSameAs(step);
        } finally {
            StepSynchronizationManager.close();
        }
    }

    @Test
    void ratioSkipPolicySkipsDataErrorsUntilTheRatio() {
        StepExecution step = step(false);
        step.setReadCount(50);
        RatioSkipPolicy policy = new RatioSkipPolicy(new ErrorClassifier(), 0.2, 100, () -> step);

        assertThat(policy.shouldSkip(new DeserializationException("bad", null), 0))
                .isTrue();
        assertThat(policy.shouldSkip(new TransientInfraException("down", null), 0))
                .isFalse();
        assertThat(policy.shouldSkip(new DeserializationException("bad", null), 19))
                .as("20 of max(50, 100) is exactly the limit")
                .isTrue();
        assertThatThrownBy(() -> policy.shouldSkip(new DeserializationException("bad", null), 20))
                .isInstanceOf(SkipLimitExceededException.class)
                .hasMessage("Skip ratio 0.210 (21 of 100 items) is above the limit 0.200");

        step.setReadCount(1000);
        assertThat(policy.shouldSkip(new DeserializationException("bad", null), 150))
                .isTrue();
    }

    @Test
    void transientRetryPolicyRetriesInfrastructureErrorsOnly() {
        TransientRetryPolicy policy = new TransientRetryPolicy(new ErrorClassifier(), 3);
        RetryContext context = policy.open(null);
        assertThat(policy.canRetry(context)).isTrue();
        policy.registerThrowable(context, new DataIntegrityViolationException("23514"));
        assertThat(policy.canRetry(context)).isFalse();

        RetryContext transientContext = policy.open(null);
        policy.registerThrowable(transientContext, new TransientInfraException("down", null));
        assertThat(policy.canRetry(transientContext)).isTrue();
        policy.registerThrowable(transientContext, new TransientInfraException("down", null));
        policy.registerThrowable(transientContext, new TransientInfraException("down", null));
        assertThat(policy.canRetry(transientContext)).as("three attempts").isFalse();

        ExponentialRandomBackOffPolicy backOff =
                TransientRetryPolicy.backOff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(16));
        assertThat(backOff.getInitialInterval()).isEqualTo(1000);
        assertThat(backOff.getMaxInterval()).isEqualTo(16000);
    }

    @Test
    void skipAwareExitListenerMarksStepsWithDeadLetters() {
        SkipAwareExitListener listener = new SkipAwareExitListener();
        StepExecution clean = step(false);
        clean.setExitStatus(ExitStatus.COMPLETED);
        assertThat(listener.afterStep(clean)).isEqualTo(ExitStatus.COMPLETED);

        StepExecution skipped = step(false);
        skipped.setExitStatus(ExitStatus.COMPLETED);
        skipped.setProcessSkipCount(1);
        assertThat(listener.afterStep(skipped).getExitCode()).isEqualTo("COMPLETED_WITH_SKIPS");

        StepExecution rejected = step(false);
        rejected.setExitStatus(ExitStatus.COMPLETED);
        StepValues.addRejected(rejected, 2);
        assertThat(listener.afterStep(rejected).getExitDescription()).contains("2 item(s)");

        StepExecution failed = step(false);
        failed.setExitStatus(ExitStatus.FAILED);
        failed.setProcessSkipCount(3);
        assertThat(listener.afterStep(failed)).isEqualTo(ExitStatus.FAILED);
    }

    @Test
    void batchIdStepListenerRecordsTheStepAndPutsItsIdInTheContext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        StepExecution step = MetaDataInstanceFactory.createStepExecution();
        BatchIdStepListener listener = new BatchIdStepListener(jdbc);

        listener.beforeStep(step);

        String batchId = step.getExecutionContext().getString(StepValues.BATCH_ID);
        assertThat(UUID.fromString(batchId).version()).isEqualTo(7);
        verify(jdbc).update(any(String.class), any(Object[].class));
        assertThat(listener.afterStep(step)).isEqualTo(step.getExitStatus());
    }

    private static final InboundMessage MESSAGE =
            EtlFixtures.message(EtlSource.TICKETING_SALES, EtlFixtures.ticketSale());

    private static MessageProcessors processors(MessageProcessor processor) {
        return new MessageProcessors(List.of(processor));
    }

    private static MessageProcessor processor(String key) {
        MessageProcessor processor = mock(MessageProcessor.class);
        when(processor.source()).thenReturn(EtlSource.TICKETING_SALES);
        when(processor.businessKey(any())).thenReturn(key);
        return processor;
    }

    @Test
    void deadLetterSkipListenerWritesProcessWriteAndReadSkips() {
        DeadLetterWriter writer = mock(DeadLetterWriter.class);
        WriteStats stats = new WriteStats(new SimpleMeterRegistry());
        StepExecution step = step(false);
        DeadLetterSkipListener listener =
                new DeadLetterSkipListener(writer, processors(processor("TX-1")), stats, () -> step);

        listener.onSkipInProcess(MESSAGE, new DataException(DlqStage.QUALITY, "DQ-09", "negative", null));
        listener.onSkipInProcess(MESSAGE, new IllegalArgumentException("odd"));
        WriteSet set = mock(WriteSet.class);
        when(set.origin()).thenReturn(MESSAGE);
        when(set.businessKey()).thenReturn("TX-2");
        listener.onSkipInWrite(set, new DataIntegrityViolationException("check"));
        listener.onSkipInRead(
                new UnreadableRecordException(MESSAGE, new DeserializationException("broken line", null)));
        listener.onSkipInRead(new IllegalStateException("no record"));

        ArgumentCaptor<DeadLetter> letters = ArgumentCaptor.forClass(DeadLetter.class);
        verify(writer, org.mockito.Mockito.times(4)).write(letters.capture());
        List<DeadLetter> all = letters.getAllValues();
        assertThat(all)
                .extracting(DeadLetter::stage)
                .containsExactly(DlqStage.QUALITY, DlqStage.DESERIALIZE, DlqStage.LOAD, DlqStage.DESERIALIZE);
        assertThat(all).extracting(DeadLetter::businessKey).containsExactly("TX-1", "TX-1", "TX-2", null);
        assertThat(all).extracting(DeadLetter::batchId).containsOnly(BATCH_ID);
        verify(writer, never()).writeReplay(any());
    }

    @Test
    void deadLetterSkipListenerUpdatesOnReplayAndSurvivesAMissingKey() {
        DeadLetterWriter writer = mock(DeadLetterWriter.class);
        MessageProcessor processor = processor(null);
        when(processor.businessKey(any())).thenThrow(new IllegalStateException("unparsable"));
        StepExecution step = step(true);
        DeadLetterSkipListener listener = new DeadLetterSkipListener(
                writer, processors(processor), new WriteStats(new SimpleMeterRegistry()), () -> step);

        listener.onSkipInProcess(MESSAGE, new DeserializationException("bad", null));

        ArgumentCaptor<DeadLetter> letter = ArgumentCaptor.forClass(DeadLetter.class);
        verify(writer).writeReplay(letter.capture());
        assertThat(letter.getValue().businessKey()).isNull();
    }

    @Test
    void batchChunkWriterPassesTheStepContextAndCountsRejections() {
        List<WriteContext> contexts = new ArrayList<>();
        ChunkWriter delegate = (items, context) -> {
            contexts.add(context);
            return new WriteOutcome(List.copyOf(items), 0, 0, 0, 1);
        };
        StepExecution step = step(true);
        BatchChunkWriter writer = new BatchChunkWriter(delegate, CLOCK, () -> step);

        writer.write(new Chunk<>(List.of(mock(WriteSet.class), mock(WriteSet.class))));

        assertThat(contexts).singleElement().satisfies(c -> {
            assertThat(c.batchId()).isEqualTo(BATCH_ID);
            assertThat(c.replay()).isTrue();
            assertThat(c.businessNow()).isEqualTo(EtlFixtures.NOW);
        });
        assertThat(StepValues.rejected(step)).isEqualTo(1);
    }

    @Test
    void processorRouterUsesTheProcessorOfTheSourceAndTheReplayFlag() {
        List<RuleContext> contexts = new ArrayList<>();
        WriteSet result = mock(WriteSet.class);
        MessageProcessor processor = processor("TX-1");
        when(processor.process(any(), any())).thenAnswer(call -> {
            contexts.add(call.getArgument(1));
            return result;
        });
        StepExecution step = step(true);
        MessageProcessorRouter router =
                new MessageProcessorRouter(processors(processor), CLOCK, new ReferenceDataHolder(), () -> step);

        assertThat(router.process(MESSAGE)).isSameAs(result);
        assertThat(contexts)
                .singleElement()
                .satisfies(c -> assertThat(c.replay()).isTrue());
    }

    @Test
    void faultStepListenerHitsEveryBatchFaultPoint() {
        List<FaultPoint> hits = new ArrayList<>();
        FaultInjector injector = hits::add;
        FaultStepListener listener = new FaultStepListener(injector);

        listener.beforeChunk((org.springframework.batch.core.scope.context.ChunkContext) null);
        listener.beforeProcess("item");
        listener.afterProcess("item", "result");
        listener.beforeWrite(new Chunk<>());
        listener.afterWrite(new Chunk<>());

        assertThat(hits)
                .containsExactly(
                        FaultPoint.BEFORE_READ,
                        FaultPoint.BEFORE_PROCESS,
                        FaultPoint.AFTER_PROCESS,
                        FaultPoint.BEFORE_WRITE,
                        FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
    }
}
