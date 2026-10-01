package dev.pti.api.etlops.config;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.api.etlops.adapter.out.validation.JsonEditedPayloadChecker;
import dev.pti.api.etlops.application.ConfirmDeadLetterReplay;
import dev.pti.api.etlops.application.DiscardDeadLetter;
import dev.pti.api.etlops.application.EditDeadLetterPayload;
import dev.pti.api.etlops.application.EstimateRawReplay;
import dev.pti.api.etlops.application.GetBatchLineage;
import dev.pti.api.etlops.application.GetDeadLetter;
import dev.pti.api.etlops.application.GetDeadLetterSummary;
import dev.pti.api.etlops.application.GetJobRequest;
import dev.pti.api.etlops.application.GetJobRun;
import dev.pti.api.etlops.application.GetJobSummary;
import dev.pti.api.etlops.application.GetReplay;
import dev.pti.api.etlops.application.GetRuntimeFlag;
import dev.pti.api.etlops.application.ListDeadLetterActions;
import dev.pti.api.etlops.application.ListDeadLetters;
import dev.pti.api.etlops.application.ListFeedVersions;
import dev.pti.api.etlops.application.ListJobRuns;
import dev.pti.api.etlops.application.ListReplays;
import dev.pti.api.etlops.application.ListRuntimeFlags;
import dev.pti.api.etlops.application.ReplayDeadLetter;
import dev.pti.api.etlops.application.RequestJobRestart;
import dev.pti.api.etlops.application.RequestJobRun;
import dev.pti.api.etlops.application.RequestJobStop;
import dev.pti.api.etlops.application.RequestRawReplay;
import dev.pti.api.etlops.application.ResolveDeadLetter;
import dev.pti.api.etlops.application.UpdateRuntimeFlag;
import dev.pti.api.etlops.application.port.BatchLineageReader;
import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.EditedPayloadChecker;
import dev.pti.api.etlops.application.port.FeedVersionReader;
import dev.pti.api.etlops.application.port.JobRequestReader;
import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.application.port.RuntimeFlagStore;
import dev.pti.api.etlops.domain.GrafanaLinks;
import dev.pti.api.etlops.domain.RawReplayRules;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.message.MessageSchemas;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code etlops} feature: the ETL operations endpoints for jobs, dead letters, replays and runtime flags
 * (DOC-32 §6…§9). Reads go through {@code readerTx} on the replica, every write through {@code operatorTx} on the
 * primary as {@code replay_operator} (DOC-31 §10.1). The JDBC adapters are components: they name the query timer of the
 * platform in their constructors, which a {@code config} class may not (A-14).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LinksProperties.class, ReplayProperties.class, ReplayEstimateProperties.class})
class EtlOpsConfiguration {

    /** UUIDv7: the ids of the requests are time ordered, as DOC-32 E-33 says. */
    private static final Supplier<UUID> IDS = UuidCreator::getTimeOrderedEpoch;

    @Bean
    GrafanaLinks grafanaLinks(LinksProperties links) {
        return new GrafanaLinks(links.grafanaUrl());
    }

    @Bean
    RawReplayRules rawReplayRules(ReplayProperties replay) {
        return new RawReplayRules(replay.maxWindow(), replay.rawSettle(), replay.rawMaxAge());
    }

    @Bean
    EditedPayloadChecker editedPayloadChecker(
            Validator validator, @Value("${pti.pii.blocklist:customer_ref}") List<String> blocklist) {
        return new JsonEditedPayloadChecker(MessageSchemas.load(), validator, new PiiScrubber(blocklist));
    }

    // ------------------------------------------------------------------------------------------ jobs (E-30…E-38)

    @Bean
    ListJobRuns listJobRuns(JobRunReader runs, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListJobRuns(runs, tx);
    }

    @Bean
    GetJobSummary getJobSummary(JobRunReader runs, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetJobSummary(runs, tx);
    }

    @Bean
    GetJobRun getJobRun(JobRunReader runs, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetJobRun(runs, tx);
    }

    @Bean
    GetJobRequest getJobRequest(JobRequestReader requests, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetJobRequest(requests, tx);
    }

    @Bean
    RequestJobRun requestJobRun(
            RequireActiveFeed requireActiveFeed,
            JobRequestStore store,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new RequestJobRun(requireActiveFeed, store, operatorTx, metrics, clock, IDS);
    }

    @Bean
    RequestJobRestart requestJobRestart(
            JobRunReader runs,
            JobRequestStore store,
            @Qualifier("readerTx") TransactionRunner readerTx,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new RequestJobRestart(runs, store, readerTx, operatorTx, metrics, clock, IDS);
    }

    @Bean
    RequestJobStop requestJobStop(
            JobRunReader runs,
            JobRequestStore store,
            @Qualifier("readerTx") TransactionRunner readerTx,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new RequestJobStop(runs, store, readerTx, operatorTx, metrics, clock, IDS);
    }

    @Bean
    GetBatchLineage getBatchLineage(BatchLineageReader lineage, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetBatchLineage(lineage, tx);
    }

    @Bean
    ListFeedVersions listFeedVersions(FeedVersionReader feeds, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListFeedVersions(feeds, tx);
    }

    // ------------------------------------------------------------------------------------ dead letters (E-40…E-48)

    @Bean
    ListDeadLetters listDeadLetters(DeadLetterReader letters, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListDeadLetters(letters, tx);
    }

    @Bean
    GetDeadLetterSummary getDeadLetterSummary(
            DeadLetterReader letters, BusinessClock clock, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetDeadLetterSummary(letters, clock, tx);
    }

    @Bean
    GetDeadLetter getDeadLetter(DeadLetterReader letters, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetDeadLetter(letters, tx);
    }

    @Bean
    ListDeadLetterActions listDeadLetterActions(DeadLetterReader letters, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListDeadLetterActions(letters, tx);
    }

    @Bean
    EditDeadLetterPayload editDeadLetterPayload(
            DeadLetterStore letters,
            EditedPayloadChecker checker,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new EditDeadLetterPayload(letters, checker, operatorTx, events, metrics, clock);
    }

    @Bean
    ReplayDeadLetter replayDeadLetter(
            DeadLetterStore letters,
            ReplayRequestStore replays,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new ReplayDeadLetter(letters, replays, operatorTx, events, metrics, clock, IDS);
    }

    @Bean
    ConfirmDeadLetterReplay confirmDeadLetterReplay(
            DeadLetterStore letters,
            ReplayRequestStore replays,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new ConfirmDeadLetterReplay(letters, replays, operatorTx, events, metrics, clock, IDS);
    }

    @Bean
    DiscardDeadLetter discardDeadLetter(
            DeadLetterStore letters,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new DiscardDeadLetter(letters, operatorTx, events, metrics, clock);
    }

    @Bean
    ResolveDeadLetter resolveDeadLetter(
            DeadLetterStore letters,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new ResolveDeadLetter(letters, operatorTx, events, metrics, clock);
    }

    // ------------------------------------------------------------------------------------------- replays (E-50…E-53)

    @Bean
    RequestRawReplay requestRawReplay(
            ReplayRequestStore store,
            @Qualifier("operatorTx") TransactionRunner operatorTx,
            RawReplayRules rules,
            WriteMetrics metrics,
            BusinessClock clock) {
        return new RequestRawReplay(store, operatorTx, rules, metrics, clock, IDS);
    }

    @Bean
    ListReplays listReplays(ReplayReader replays, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListReplays(replays, tx);
    }

    @Bean
    GetReplay getReplay(ReplayReader replays, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetReplay(replays, tx);
    }

    @Bean
    EstimateRawReplay estimateRawReplay(
            ReplayReader replays,
            RawReplayRules rules,
            BusinessClock clock,
            ReplayEstimateProperties estimate,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new EstimateRawReplay(replays, rules, clock, estimate.throughput(), tx);
    }

    // ------------------------------------------------------------------------------------------- flags (E-55…E-57)

    @Bean
    ListRuntimeFlags listRuntimeFlags(RuntimeFlagReader flags, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListRuntimeFlags(flags, tx);
    }

    @Bean
    GetRuntimeFlag getRuntimeFlag(RuntimeFlagReader flags, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetRuntimeFlag(flags, tx);
    }

    @Bean
    UpdateRuntimeFlag updateRuntimeFlag(
            RuntimeFlagStore store, @Qualifier("operatorTx") TransactionRunner operatorTx, WriteMetrics metrics) {
        return new UpdateRuntimeFlag(store, operatorTx, metrics);
    }
}
