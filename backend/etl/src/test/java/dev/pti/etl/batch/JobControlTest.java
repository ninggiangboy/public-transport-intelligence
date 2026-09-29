package dev.pti.etl.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.common.time.BusinessClock;
import dev.pti.etl.batch.JobRequests.JobRequest;
import dev.pti.etl.batch.JobRequests.Kind;
import dev.pti.etl.testing.EtlFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class JobControlTest {

    private static final BusinessClock CLOCK =
            new BusinessClock(Clock.fixed(EtlFixtures.NOW, ZoneOffset.UTC), Duration.ZERO);

    private static Job job(PtiJob job, boolean restartable) {
        Job bean = mock(Job.class);
        when(bean.getName()).thenReturn(job.jobName());
        when(bean.isRestartable()).thenReturn(restartable);
        return bean;
    }

    private static JobExecution execution(long id, String jobName, BatchStatus status) {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution(jobName, 1L, id);
        execution.setStatus(status);
        return execution;
    }

    @Test
    void theCatalogKnowsEveryJobOfDoc19() {
        assertThat(PtiJob.byName("GtfsStaticLoadJob")).contains(PtiJob.GTFS_STATIC_LOAD);
        assertThat(PtiJob.byName("NoSuchJob")).isEmpty();
        assertThat(PtiJob.DLQ_REPLAY.manualRun()).isFalse();
        assertThat(PtiJob.DATA_QUALITY.stale()).isEqualTo(PtiJob.Stale.FAIL);
        assertThat(PtiJob.GTFS_STATIC_LOAD.extraParameters()).containsExactlyInAnyOrder("sourceUri", "allowReactivate");
        assertThat(PtiJob.RAW_ZONE_REPLAY.identity().parameter()).isEqualTo("replayRequestId");
    }

    @Test
    void jobParamsBuildTheIdentifyingParameter() {
        assertThat(JobParams.runDate(PtiJob.OPS_RETENTION, LocalDate.parse("2026-09-29"))
                        .getString("runDate"))
                .isEqualTo("2026-09-29");
        assertThat(JobParams.slot(PtiJob.DATA_QUALITY, Instant.parse("2026-09-29T21:23:41Z"), 5)
                        .getString("slot"))
                .isEqualTo("2026-09-29T21:20:00Z");
        assertThat(JobParams.runKey(PtiJob.GTFS_STATIC_LOAD, "startup:2026-09-29")
                        .getString("runKey"))
                .isEqualTo("startup:2026-09-29");
        UUID request = UUID.randomUUID();
        JobParameters replay = JobParams.replay(PtiJob.DLQ_REPLAY, request);
        assertThat(replay.getString("replayRequestId")).isEqualTo(request.toString());
        assertThat(replay.getString("replay")).isEqualTo("true");
        assertThat(replay.getIdentifyingParameters()).hasSize(1);
    }

    @Test
    void jobRequestParametersAreReadAsText() {
        assertThat(JobRequests.parameters(null)).isEmpty();
        assertThat(JobRequests.parameters("{\"runDate\": \"2026-09-29\", \"force\": true, \"n\": 3}"))
                .containsExactly(Map.entry("runDate", "2026-09-29"), Map.entry("force", "true"), Map.entry("n", "3"));
        assertThat(JobRequests.truncate("x".repeat(2000)))
                .hasSize(JobRequests.MAX_MESSAGE)
                .endsWith("…");
        assertThat(JobRequests.truncate("short")).isEqualTo("short");
    }

    @Test
    void theLauncherTreatsASecondTriggerAsNothingToDo() throws Exception {
        JobOperator operator = mock(JobOperator.class);
        Job job = job(PtiJob.OPS_RETENTION, true);
        PtiJobLauncher launcher = new PtiJobLauncher(operator, List.of(job, job(PtiJob.DATA_QUALITY, true)));
        JobParameters parameters = JobParams.runDate(PtiJob.OPS_RETENTION, LocalDate.parse("2026-09-29"));
        JobExecution started = execution(1, "OpsRetentionJob", BatchStatus.STARTING);
        when(operator.start(job, parameters))
                .thenReturn(started)
                .thenThrow(new JobExecutionAlreadyRunningException("running"))
                .thenThrow(new JobInstanceAlreadyCompleteException("done"))
                .thenThrow(new DuplicateKeyException("job_inst_un"))
                .thenThrow(new JobRestartException("no"));

        assertThat(launcher.launchIfNew(PtiJob.OPS_RETENTION, parameters)).contains(started);
        for (int i = 0; i < 4; i++) {
            assertThat(launcher.launchIfNew(PtiJob.OPS_RETENTION, parameters)).isEmpty();
        }
        assertThat(launcher.job(PtiJob.GTFS_STATIC_LOAD)).isEmpty();
        assertThatThrownBy(() -> launcher.start(PtiJob.GTFS_STATIC_LOAD, parameters))
                .isInstanceOf(IllegalStateException.class);
    }

    /** A poller over mocks; the tests script the requests. */
    private static final class PollerFixture {
        final JobRequests requests = mock(JobRequests.class);
        final JobOperator operator = mock(JobOperator.class);
        final JobRepository repository = mock(JobRepository.class);
        final Job retention = job(PtiJob.OPS_RETENTION, true);
        final Job dedup = job(PtiJob.DEDUP_REGISTRY_CLEANUP, false);
        final PtiJobLauncher launcher = new PtiJobLauncher(
                operator, List.of(retention, dedup, job(PtiJob.GTFS_STATIC_LOAD, true), job(PtiJob.DLQ_REPLAY, true)));
        boolean room = true;
        final JobRequestPoller poller = new JobRequestPoller(
                requests,
                launcher,
                operator,
                repository,
                CLOCK,
                () -> room,
                new SimpleMeterRegistry(),
                List.of((job, name, value) -> {
                    if (name.equals("sourceUri") && value.startsWith("file:/etc")) {
                        throw new IllegalArgumentException(
                                "sourceUri " + value + " is outside the allowed directories");
                    }
                }));

        JobRequest run(String job, Map<String, String> parameters) {
            return new JobRequest(UUID.randomUUID(), Kind.RUN, job, parameters, null);
        }

        String rejection(JobRequest request) {
            poller.handle(request);
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(requests).reject(eq(request.id()), message.capture());
            return message.getValue();
        }
    }

    @Test
    void aRunRequestStartsTheJobAndLinksTheExecution() throws Exception {
        PollerFixture f = new PollerFixture();
        JobRequest request = f.run("OpsRetentionJob", Map.of("runDate", "2026-09-28"));
        JobExecution started = execution(7, "OpsRetentionJob", BatchStatus.STARTED);
        ArgumentCaptor<JobParameters> parameters = ArgumentCaptor.forClass(JobParameters.class);
        when(f.operator.start(eq(f.retention), parameters.capture())).thenReturn(started);
        when(f.repository.getJobExecution(7L)).thenReturn(execution(7, "OpsRetentionJob", BatchStatus.COMPLETED));
        when(f.requests.claim()).thenReturn(Optional.of(request)).thenReturn(Optional.empty());

        f.poller.poll();

        verify(f.requests).started(request.id(), 7L);
        verify(f.requests).finish(any());
        assertThat(parameters.getValue().getString("runDate")).isEqualTo("2026-09-28");
        assertThat(parameters.getValue().getString(JobParams.JOB_REQUEST_ID))
                .isEqualTo(request.id().toString());
        assertThat(parameters.getValue().getIdentifyingParameters())
                .extracting("name")
                .containsExactly("runDate");
    }

    @Test
    void identifyingParametersGetDefaults() {
        PollerFixture f = new PollerFixture();
        JobRequest gtfs = f.run("GtfsStaticLoadJob", Map.of("sourceUri", "file:/feed/a.zip"));
        assertThat(f.poller
                        .parameters(PtiJob.GTFS_STATIC_LOAD, gtfs)
                        .toJobParameters()
                        .getString("runKey"))
                .isEqualTo("manual:" + gtfs.id());
        JobRequest retention = f.run("OpsRetentionJob", Map.of());
        assertThat(f.poller
                        .parameters(PtiJob.OPS_RETENTION, retention)
                        .toJobParameters()
                        .getString("runDate"))
                .isEqualTo("2026-09-29");
        JobRequest dedup = f.run("DedupRegistryCleanupJob", Map.of("slot", "2026-09-29T21:15:00Z"));
        assertThat(f.poller
                        .parameters(PtiJob.DEDUP_REGISTRY_CLEANUP, dedup)
                        .toJobParameters()
                        .getString("slot"))
                .isEqualTo("2026-09-29T21:15:00Z");
    }

    @Test
    void badRunRequestsAreRejectedWithAReason() throws Exception {
        PollerFixture f = new PollerFixture();
        assertThat(f.rejection(f.run("NoSuchJob", Map.of()))).isEqualTo("Unknown job NoSuchJob");
        assertThat(f.rejection(f.run("DlqReplayJob", Map.of()))).isEqualTo("Unknown job DlqReplayJob");
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of("force", "true"))))
                .isEqualTo("Parameter force is not allowed for OpsRetentionJob");
        assertThat(f.rejection(f.run("GtfsStaticLoadJob", Map.of("runKey", "x"))))
                .contains("Parameter runKey is not allowed");
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of("runDate", "soon"))))
                .contains("not a valid run_date");
        assertThat(f.rejection(f.run("DedupRegistryCleanupJob", Map.of("slot", "now"))))
                .contains("not a valid slot");
        assertThat(f.rejection(f.run("GtfsStaticLoadJob", Map.of("sourceUri", "file:/etc/hosts"))))
                .contains("outside the allowed directories");

        when(f.operator.start(eq(f.retention), any()))
                .thenThrow(new JobExecutionAlreadyRunningException("r"))
                .thenThrow(new JobInstanceAlreadyCompleteException("c"))
                .thenThrow(new JobRestartException("nope"))
                .thenThrow(new IllegalStateException("boom"));
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of()))).contains("already running");
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of()))).contains("already completed");
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of()))).isEqualTo("nope");
        assertThat(f.rejection(f.run("OpsRetentionJob", Map.of()))).isEqualTo("Could not start: boom");
    }

    @Test
    void aFullExecutorLeavesTheRequestPending() throws Exception {
        PollerFixture f = new PollerFixture();
        JobRequest request = f.run("OpsRetentionJob", Map.of());
        when(f.operator.start(eq(f.retention), any())).thenThrow(new TaskRejectedException("full"));
        f.poller.handle(request);
        verify(f.requests).release(request.id());

        f.room = false;
        f.poller.poll();
        verify(f.requests, never()).claim();
        verify(f.requests, org.mockito.Mockito.times(1)).failInterrupted(JobRequestPoller.INTERRUPTED_AFTER);
    }

    @Test
    void restartAndStopRequestsCheckTheirTarget() throws Exception {
        PollerFixture f = new PollerFixture();
        JobRequest missing = new JobRequest(UUID.randomUUID(), Kind.RESTART, "OpsRetentionJob", Map.of(), 99L);
        assertThat(f.rejection(missing)).isEqualTo("No job execution 99");

        JobExecution completed = execution(5, "OpsRetentionJob", BatchStatus.COMPLETED);
        when(f.repository.getJobExecution(5L)).thenReturn(completed);
        assertThat(f.rejection(new JobRequest(UUID.randomUUID(), Kind.RESTART, "x", Map.of(), 5L)))
                .isEqualTo("Execution 5 is COMPLETED, not FAILED or STOPPED");

        JobExecution notRestartable = execution(6, "DedupRegistryCleanupJob", BatchStatus.FAILED);
        when(f.repository.getJobExecution(6L)).thenReturn(notRestartable);
        assertThat(f.rejection(new JobRequest(UUID.randomUUID(), Kind.RESTART, "x", Map.of(), 6L)))
                .contains("cannot be restarted");

        JobExecution failed = execution(8, "OpsRetentionJob", BatchStatus.FAILED);
        when(f.repository.getJobExecution(8L)).thenReturn(failed);
        when(f.operator.restart(failed))
                .thenReturn(execution(9, "OpsRetentionJob", BatchStatus.STARTED))
                .thenThrow(new JobRestartException("already running"));
        when(f.repository.getJobExecution(9L)).thenReturn(execution(9, "OpsRetentionJob", BatchStatus.STARTED));
        JobRequest restart = new JobRequest(UUID.randomUUID(), Kind.RESTART, "x", Map.of(), 8L);
        f.poller.handle(restart);
        verify(f.requests).started(restart.id(), 9L);
        verify(f.requests, never()).finish(any());
        assertThat(f.rejection(new JobRequest(UUID.randomUUID(), Kind.RESTART, "x", Map.of(), 8L)))
                .isEqualTo("already running");

        JobRequest stop = new JobRequest(UUID.randomUUID(), Kind.STOP, "x", Map.of(), 8L);
        when(f.operator.stop(failed)).thenReturn(true).thenThrow(new JobExecutionNotRunningException("no"));
        f.poller.handle(stop);
        verify(f.requests).done(stop.id(), 8L, "Stop requested");
        assertThat(f.rejection(new JobRequest(UUID.randomUUID(), Kind.STOP, "x", Map.of(), 8L)))
                .isEqualTo("Execution 8 is not running");
    }

    @Test
    void theJobListenerRecordsTheEndAndCleansTheMdc() {
        JobRequests requests = mock(JobRequests.class);
        JobRequestListener listener = new JobRequestListener(requests);
        JobExecution execution = execution(3, "OpsRetentionJob", BatchStatus.COMPLETED);

        listener.beforeJob(execution);
        assertThat(org.slf4j.MDC.get(JobRequestListener.MDC_JOB)).isEqualTo("OpsRetentionJob");
        listener.afterJob(execution);

        verify(requests).finish(execution);
        assertThat(org.slf4j.MDC.get(JobRequestListener.MDC_JOB)).isNull();
    }

    /** Recoverer over mocks: one stale execution of the given job. */
    private static final class RecovererFixture {
        final JobRepository repository = mock(JobRepository.class);
        final JobOperator operator = mock(JobOperator.class);
        final JobRequests requests = mock(JobRequests.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final LocalDateTime now = LocalDateTime.parse("2026-09-29T21:20:00");
        final StaleExecutionRecoverer recoverer;

        RecovererFixture(List<Job> jobs) {
            recoverer = new StaleExecutionRecoverer(
                    repository,
                    operator,
                    new PtiJobLauncher(operator, jobs),
                    requests,
                    jdbc,
                    Duration.ofMinutes(2),
                    Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC),
                    new SimpleMeterRegistry());
        }

        JobExecution running(String jobName, long id, Duration age) {
            JobExecution execution = execution(id, jobName, BatchStatus.STARTED);
            execution.setLastUpdated(now.minus(age));
            StepExecution step = MetaDataInstanceFactory.createStepExecution(execution, "step", id * 10);
            step.setLastUpdated(now.minus(age));
            if (execution.getStepExecutions().isEmpty()) {
                execution.addStepExecution(step);
            }
            when(repository.getJobNames()).thenReturn(List.of(jobName));
            when(repository.findRunningJobExecutions(jobName)).thenReturn(Set.of(execution));
            when(operator.recover(execution)).thenAnswer(call -> {
                execution.setStatus(BatchStatus.FAILED);
                step.getExecutionContext().put(StaleExecutionRecoverer.RECOVERED, true);
                return execution;
            });
            return execution;
        }
    }

    @Test
    void aStaleRestartableExecutionIsFailedAndRestarted() throws Exception {
        RecovererFixture f = new RecovererFixture(List.of(job(PtiJob.OPS_RETENTION, true)));
        JobExecution stale = f.running("OpsRetentionJob", 4, Duration.ofMinutes(3));
        when(f.operator.restart(stale)).thenReturn(execution(5, "OpsRetentionJob", BatchStatus.STARTED));

        assertThat(f.recoverer.recover()).containsExactly(stale);

        assertThat(stale.getExitStatus().getExitCode()).isEqualTo(StaleExecutionRecoverer.STALE);
        assertThat(stale.getStepExecutions().iterator().next().getExitStatus().getExitCode())
                .isEqualTo("STALE");
        verify(f.operator).restart(stale);
        verify(f.requests).finish(stale);
    }

    @Test
    void aRecentExecutionIsLeftAlone() {
        RecovererFixture f = new RecovererFixture(List.of(job(PtiJob.OPS_RETENTION, true)));
        JobExecution recent = f.running("OpsRetentionJob", 4, Duration.ofSeconds(30));

        assertThat(f.recoverer.recover()).isEmpty();
        verify(f.operator, never()).recover(recent);
    }

    @Test
    void staleReplayAndSlotJobsAreOnlyFailed() throws Exception {
        RecovererFixture replay = new RecovererFixture(List.of(job(PtiJob.DLQ_REPLAY, true)));
        JobExecution stale = replay.running("DlqReplayJob", 4, Duration.ofMinutes(5));
        assertThat(replay.recoverer.recover()).containsExactly(stale);
        verify(replay.jdbc).update(anyString(), eq(4L));
        verify(replay.operator, never()).restart(any(JobExecution.class));

        RecovererFixture slot = new RecovererFixture(List.of(job(PtiJob.DATA_QUALITY, true)));
        slot.running("DataQualityJob", 6, Duration.ofMinutes(5));
        assertThat(slot.recoverer.recover()).hasSize(1);
        verify(slot.operator, never()).restart(any(JobExecution.class));

        RecovererFixture unknown = new RecovererFixture(List.of());
        unknown.running("SomeOldJob", 7, Duration.ofMinutes(5));
        assertThat(unknown.recoverer.recover()).hasSize(1);

        RecovererFixture failing = new RecovererFixture(List.of(job(PtiJob.OPS_RETENTION, true)));
        JobExecution broken = failing.running("OpsRetentionJob", 8, Duration.ofMinutes(5));
        when(failing.operator.recover(broken)).thenThrow(new IllegalStateException("db down"));
        assertThat(failing.recoverer.recover()).isEmpty();

        RecovererFixture restartFails = new RecovererFixture(List.of(job(PtiJob.OPS_RETENTION, true)));
        JobExecution again = restartFails.running("OpsRetentionJob", 9, Duration.ofMinutes(5));
        when(restartFails.operator.restart(again)).thenThrow(new JobRestartException("running"));
        assertThat(restartFails.recoverer.recover()).containsExactly(again);
    }

    @Test
    void schedulesLaunchWithTheRightIdentity() {
        PtiJobLauncher launcher = mock(PtiJobLauncher.class);
        when(launcher.job(any())).thenReturn(Optional.of(mock(Job.class)));
        JobRequestPoller poller = mock(JobRequestPoller.class);
        StaleExecutionRecoverer recoverer = mock(StaleExecutionRecoverer.class);
        LockingTaskExecutor locks = mock(LockingTaskExecutor.class);
        AtomicInteger startup = new AtomicInteger();
        BusinessClock offset =
                new BusinessClock(Clock.fixed(Instant.parse("2026-09-29T03:00:00Z"), ZoneOffset.UTC), Duration.ZERO);
        BatchSchedules schedules = new BatchSchedules(
                launcher,
                poller,
                recoverer,
                locks,
                offset,
                ZoneId.of("America/Chicago"),
                List.of(startup::incrementAndGet, () -> {
                    throw new IllegalStateException("broken startup task");
                }));

        schedules.pollJobRequests();
        schedules.recoverStaleExecutions();
        verify(poller, never()).poll();
        verify(recoverer, never()).recover();

        schedules.onReady();
        assertThat(schedules.isReady()).isTrue();
        assertThat(startup).hasValue(1);
        verify(locks).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
        verify(launcher)
                .launchIfNew(
                        PtiJob.PARTITION_MAINTENANCE,
                        JobParams.runDate(PtiJob.PARTITION_MAINTENANCE, LocalDate.parse("2026-09-28")));

        schedules.pollJobRequests();
        schedules.recoverStaleExecutions();
        schedules.opsRetention();
        schedules.batchMetadataCleanup();
        schedules.dedupRegistryCleanup();
        schedules.dataQuality();
        verify(poller).poll();
        verify(recoverer).recover();
        verify(launcher)
                .launchIfNew(
                        PtiJob.OPS_RETENTION, JobParams.runDate(PtiJob.OPS_RETENTION, LocalDate.parse("2026-09-29")));
        verify(launcher)
                .launchIfNew(
                        PtiJob.DEDUP_REGISTRY_CLEANUP,
                        JobParams.slot(PtiJob.DEDUP_REGISTRY_CLEANUP, Instant.parse("2026-09-29T03:00:00Z"), 15));
        verify(launcher)
                .launchIfNew(
                        PtiJob.DATA_QUALITY,
                        JobParams.slot(PtiJob.DATA_QUALITY, Instant.parse("2026-09-29T03:00:00Z"), 5));
        verify(launcher)
                .launchIfNew(
                        PtiJob.BATCH_METADATA_CLEANUP,
                        JobParams.runDate(PtiJob.BATCH_METADATA_CLEANUP, LocalDate.parse("2026-09-29")));
        verify(launcher, never()).launchIfNew(eq(PtiJob.GTFS_STATIC_LOAD), any());
    }
}
