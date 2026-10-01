package dev.pti.apitest;

import dev.pti.api.etlops.application.port.BatchLineageReader;
import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.FeedVersionReader;
import dev.pti.api.etlops.application.port.JobRequestReader;
import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.application.port.RuntimeFlagStore;
import dev.pti.api.etlops.domain.ActionLogFilter;
import dev.pti.api.etlops.domain.ActionLogItem;
import dev.pti.api.etlops.domain.ActiveReplayConflict;
import dev.pti.api.etlops.domain.BatchLineage;
import dev.pti.api.etlops.domain.DeadLetterDetail;
import dev.pti.api.etlops.domain.DeadLetterFilter;
import dev.pti.api.etlops.domain.DeadLetterItem;
import dev.pti.api.etlops.domain.DeadLetterStatus;
import dev.pti.api.etlops.domain.DeadLetterSummary;
import dev.pti.api.etlops.domain.FeedVersion;
import dev.pti.api.etlops.domain.JobParameter;
import dev.pti.api.etlops.domain.JobRequest;
import dev.pti.api.etlops.domain.JobRun;
import dev.pti.api.etlops.domain.JobRunFilter;
import dev.pti.api.etlops.domain.JobSummary;
import dev.pti.api.etlops.domain.ReplayDetail;
import dev.pti.api.etlops.domain.ReplayEstimate;
import dev.pti.api.etlops.domain.ReplayFilter;
import dev.pti.api.etlops.domain.ReplayKind;
import dev.pti.api.etlops.domain.ReplayRequest;
import dev.pti.api.etlops.domain.RequestRef;
import dev.pti.api.etlops.domain.RunId;
import dev.pti.api.etlops.domain.RuntimeFlag;
import dev.pti.api.etlops.domain.StepRun;
import dev.pti.api.etlops.domain.StreamBatch;
import dev.pti.api.etlops.domain.SummaryBucket;
import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;

/**
 * The ports of the {@code etlops} feature in memory, with the write rules of the SQL: a conditional update names the
 * statuses it may start from, a repeated idempotency key inserts nothing, a second replay of a source or record that is
 * in flight is refused. A test that wants data autowires it, calls {@link #reset()} first, and holds the lock {@code
 * in-memory-etlops}.
 */
public final class InMemoryEtlOps {

    /** The database clock of the writes. */
    public static final Instant NOW = Instant.parse("2026-09-29T21:20:03.123456Z");

    public final List<JobRun> runs = new CopyOnWriteArrayList<>();
    public final Map<Long, List<JobParameter>> parameters = new ConcurrentHashMap<>();
    public final Map<Long, List<StepRun>> steps = new ConcurrentHashMap<>();
    public final Map<Long, RequestRef> requestsOf = new ConcurrentHashMap<>();
    public final List<StreamBatch> streamBatches = new CopyOnWriteArrayList<>();
    public final Map<String, Integer> batchJobStatuses = new ConcurrentHashMap<>();
    public final Map<UUID, JobRequest> jobRequests = new ConcurrentHashMap<>();
    public final Map<UUID, BatchLineage> lineages = new ConcurrentHashMap<>();
    public final List<FeedVersion> feeds = new CopyOnWriteArrayList<>();
    public final Map<UUID, DeadLetterDetail> letters = new ConcurrentHashMap<>();
    public final List<ActionLogItem> actionLog = new CopyOnWriteArrayList<>();
    public final Map<UUID, ReplayRequest> replays = new ConcurrentHashMap<>();
    public final Map<String, RuntimeFlag> flags = new ConcurrentHashMap<>();
    public volatile ReplayEstimate.History history = new ReplayEstimate.History(0, 0, 0);

    /** Every write that reached a store, to check that a repeated request writes nothing. */
    public final List<String> statements = new CopyOnWriteArrayList<>();

    private long actionIds;

    public void reset() {
        runs.clear();
        parameters.clear();
        steps.clear();
        requestsOf.clear();
        streamBatches.clear();
        batchJobStatuses.clear();
        jobRequests.clear();
        lineages.clear();
        feeds.clear();
        letters.clear();
        actionLog.clear();
        replays.clear();
        flags.clear();
        statements.clear();
        history = new ReplayEstimate.History(0, 0, 0);
        actionIds = 0;
    }

    // ----------------------------------------------------------------------------------------------- jobs

    public final JobRunReader runReader = new JobRunReader() {
        @Override
        public Page<JobRun> list(JobRunFilter filter, PageRequest page) {
            List<JobRun> rows = runs.stream()
                    .filter(run -> run.startedAt() != null
                            && !run.startedAt().isBefore(filter.from())
                            && run.startedAt().isBefore(filter.to()))
                    .filter(run -> filter.kind() == null || run.kind() == filter.kind())
                    .filter(run -> filter.names().isEmpty() || filter.names().contains(run.name()))
                    .filter(run ->
                            filter.statuses().isEmpty() || filter.statuses().contains(run.status()))
                    .sorted(Comparator.comparing(JobRun::startedAt)
                            .thenComparing(JobRun::runId)
                            .reversed())
                    .filter(run -> after(page.after(), run.startedAt(), run.runId()))
                    .limit(page.fetchSize())
                    .toList();
            return Page.of(page, rows, run -> List.of(run.startedAt().toString(), run.runId()));
        }

        @Override
        public Optional<JobRun> find(RunId id) {
            return runs.stream().filter(run -> run.runId().equals(id.text())).findFirst();
        }

        @Override
        public List<JobParameter> parameters(long jobExecutionId) {
            return parameters.getOrDefault(jobExecutionId, List.of());
        }

        @Override
        public List<StepRun> steps(long jobExecutionId) {
            return steps.getOrDefault(jobExecutionId, List.of());
        }

        @Override
        public Optional<RequestRef> requestOf(long jobExecutionId) {
            return Optional.ofNullable(requestsOf.get(jobExecutionId));
        }

        @Override
        public List<StreamBatch> streamBatches(String listenerId, Instant minute, int limit) {
            return streamBatches.stream().limit(limit).toList();
        }

        @Override
        public List<JobSummary.Row> streamSummary(Instant from, Instant to, SummaryBucket bucket) {
            return List.of();
        }

        @Override
        public Map<String, Integer> batchJobStatuses(Instant from, Instant to) {
            return Map.copyOf(batchJobStatuses);
        }
    };

    private static boolean after(@Nullable KeysetCursor cursor, Instant time, String key) {
        if (cursor == null) {
            return true;
        }
        int byTime = time.compareTo(Instant.parse(cursor.keys().get(0)));
        return byTime < 0 || (byTime == 0 && key.compareTo(cursor.keys().get(1)) < 0);
    }

    public final JobRequestReader jobRequestReader = id -> Optional.ofNullable(jobRequests.get(id));

    public final JobRequestStore jobRequestStore = new JobRequestStore() {
        @Override
        public Optional<JobRequest> findByKey(String requestedBy, String idempotencyKey) {
            return jobRequests.values().stream()
                    .filter(request -> request.requestedBy().equals(requestedBy)
                            && idempotencyKey.equals(request.idempotencyKey()))
                    .findFirst();
        }

        @Override
        public synchronized Optional<JobRequest> insert(JobRequest request) {
            if (request.idempotencyKey() != null
                    && findByKey(request.requestedBy(), request.idempotencyKey())
                            .isPresent()) {
                return Optional.empty();
            }
            statements.add("INSERT job_request " + request.kind());
            jobRequests.put(request.id(), request);
            return Optional.of(request);
        }

        @Override
        public Optional<JobRequest> find(UUID id) {
            return Optional.ofNullable(jobRequests.get(id));
        }
    };

    public final BatchLineageReader lineageReader = id -> Optional.ofNullable(lineages.get(id));

    public final FeedVersionReader feedReader =
            limit -> feeds.stream().limit(limit).toList();

    // ----------------------------------------------------------------------------------------- dead letters

    public final DeadLetterReader deadLetterReader = new DeadLetterReader() {
        @Override
        public Page<DeadLetterItem> list(DeadLetterFilter filter, PageRequest page) {
            List<DeadLetterItem> rows = letters.values().stream()
                    .map(DeadLetterDetail::item)
                    .filter(item -> filter.statuses().isEmpty()
                            || filter.statuses().contains(item.status().name()))
                    .filter(item ->
                            filter.sources().isEmpty() || filter.sources().contains(item.source()))
                    .filter(item -> filter.stages().isEmpty() || filter.stages().contains(item.stage()))
                    .filter(item -> filter.categories().isEmpty()
                            || filter.categories()
                                    .contains(
                                            item.category() == null ? DeadLetterFilter.UNCLASSIFIED : item.category()))
                    .filter(item -> filter.severities().isEmpty()
                            || filter.severities()
                                    .contains(
                                            item.severity() == null
                                                    ? DeadLetterFilter.UNCLASSIFIED
                                                    : item.severity().toString()))
                    .filter(item -> filter.ruleIds().isEmpty()
                            || (item.ruleId() != null && filter.ruleIds().contains(item.ruleId())))
                    .filter(item -> filter.from() == null || !item.createdAt().isBefore(filter.from()))
                    .filter(item -> filter.to() == null || item.createdAt().isBefore(filter.to()))
                    .toList();
            return InMemoryPaging.page(rows, page, DeadLetterItem::createdAt, DeadLetterItem::id);
        }

        @Override
        public DeadLetterSummary summary(Instant now) {
            Map<List<Object>, Long> counts = new HashMap<>();
            letters.values().stream()
                    .map(DeadLetterDetail::item)
                    .filter(item -> item.status().isOpen())
                    .forEach(item -> counts.merge(
                            List.of(item.status(), item.source(), item.severity() == null ? -1 : item.severity()),
                            1L,
                            Long::sum));
            List<DeadLetterSummary.Group> groups = new ArrayList<>();
            counts.forEach((key, n) -> groups.add(new DeadLetterSummary.Group(
                    (DeadLetterStatus) key.get(0),
                    (String) key.get(1),
                    (int) key.get(2) < 0 ? null : (Integer) key.get(2),
                    n)));
            long recent = letters.values().stream()
                    .filter(letter -> !letter.item().createdAt().isBefore(now.minusSeconds(3600)))
                    .count();
            return DeadLetterSummary.of(groups, recent);
        }

        @Override
        public Optional<DeadLetterDetail> find(UUID id) {
            return Optional.ofNullable(letters.get(id)).map(InMemoryEtlOps.this::withLog);
        }

        @Override
        public Page<ActionLogItem> actions(ActionLogFilter filter, PageRequest page) {
            List<ActionLogItem> rows = actionLog.stream()
                    .filter(item ->
                            !item.at().isBefore(filter.from()) && item.at().isBefore(filter.to()))
                    .filter(item ->
                            filter.actions().isEmpty() || filter.actions().contains(item.action()))
                    .filter(item -> filter.deadLetterId() == null
                            || filter.deadLetterId().equals(item.deadLetterId()))
                    .filter(item -> filter.actorType() == null || actorType(item.actor()) == filter.actorType())
                    .sorted(Comparator.comparing(ActionLogItem::at)
                            .thenComparing(ActionLogItem::id)
                            .reversed())
                    .filter(item -> page.after() == null
                            || item.at()
                                            .compareTo(Instant.parse(
                                                    page.after().keys().get(0)))
                                    < 0
                            || (item.at()
                                            .equals(Instant.parse(
                                                    page.after().keys().get(0)))
                                    && item.id()
                                            < Long.parseLong(page.after().keys().get(1))))
                    .limit(page.fetchSize())
                    .toList();
            return Page.of(page, rows, item -> List.of(item.at().toString(), String.valueOf(item.id())));
        }
    };

    private static ActionLogFilter.ActorType actorType(String actor) {
        if (actor.equals("auto")) {
            return ActionLogFilter.ActorType.AUTO;
        }
        return actor.startsWith("system:") ? ActionLogFilter.ActorType.SYSTEM : ActionLogFilter.ActorType.USER;
    }

    /** The detail with the lines of the log and the replays that belong to it, as the SQL joins them. */
    private DeadLetterDetail withLog(DeadLetterDetail detail) {
        UUID id = detail.item().id();
        List<DeadLetterDetail.Action> lines = actionLog.stream()
                .filter(item -> item.deadLetterId().equals(id))
                .sorted(Comparator.comparing(ActionLogItem::at).thenComparing(ActionLogItem::id))
                .map(item -> new DeadLetterDetail.Action(
                        item.at(), item.action(), item.actor(), item.confidence(), item.details()))
                .toList();
        List<DeadLetterDetail.ReplayRef> refs = replays.values().stream()
                .filter(replay -> replay.kind() == ReplayKind.DLQ_RECORD && id.equals(replay.deadLetterId()))
                .sorted(Comparator.comparing(ReplayRequest::requestedAt))
                .map(replay -> new DeadLetterDetail.ReplayRef(
                        replay.id(), replay.status(), replay.requestedBy(), replay.requestedAt(), replay.finishedAt()))
                .toList();
        return new DeadLetterDetail(
                detail.item(),
                detail.rawPayload(),
                detail.editedPayload(),
                detail.kafka(),
                detail.batchId(),
                detail.modelVersion(),
                detail.triagedAt(),
                detail.triageAttempts(),
                detail.lastReplayAt(),
                detail.resolvedBy(),
                detail.resolvedAt(),
                lines,
                refs,
                List.of());
    }

    public final DeadLetterStore deadLetterStore = new DeadLetterStore() {
        @Override
        public Optional<DeadLetterDetail> find(UUID id) {
            return deadLetterReader.find(id);
        }

        @Override
        public synchronized Optional<Changed> transition(
                UUID id, Set<DeadLetterStatus> from, DeadLetterStatus to, @Nullable String closedBy) {
            DeadLetterDetail current = letters.get(id);
            if (current == null || !from.contains(current.status())) {
                return Optional.empty();
            }
            statements.add("UPDATE dead_letter " + current.status() + " -> " + to);
            DeadLetterItem item = current.item();
            DeadLetterItem moved = new DeadLetterItem(
                    item.id(),
                    item.source(),
                    item.stage(),
                    item.ruleId(),
                    item.errorClass(),
                    item.errorMessage(),
                    to,
                    item.category(),
                    item.categoryConfidence(),
                    item.severity(),
                    item.severityConfidence(),
                    item.businessKey(),
                    item.payloadPreview(),
                    item.hasEditedPayload(),
                    item.replayCount(),
                    item.autoReplayCount(),
                    item.createdAt(),
                    NOW);
            letters.put(
                    id,
                    new DeadLetterDetail(
                            moved,
                            current.rawPayload(),
                            current.editedPayload(),
                            current.kafka(),
                            current.batchId(),
                            current.modelVersion(),
                            current.triagedAt(),
                            current.triageAttempts(),
                            current.lastReplayAt(),
                            closedBy != null ? closedBy : current.resolvedBy(),
                            closedBy != null ? NOW : current.resolvedAt(),
                            List.of(),
                            List.of(),
                            List.of()));
            return Optional.of(new Changed(current.status(), item.source(), item.categoryConfidence()));
        }

        @Override
        public synchronized Optional<Changed> saveEditedPayload(
                UUID id, Set<DeadLetterStatus> from, String payloadJson) {
            DeadLetterDetail current = letters.get(id);
            if (current == null || !from.contains(current.status())) {
                return Optional.empty();
            }
            statements.add("UPDATE dead_letter edited_payload");
            DeadLetterItem item = current.item();
            DeadLetterItem edited = new DeadLetterItem(
                    item.id(),
                    item.source(),
                    item.stage(),
                    item.ruleId(),
                    item.errorClass(),
                    item.errorMessage(),
                    item.status(),
                    item.category(),
                    item.categoryConfidence(),
                    item.severity(),
                    item.severityConfidence(),
                    item.businessKey(),
                    item.payloadPreview(),
                    true,
                    item.replayCount(),
                    item.autoReplayCount(),
                    item.createdAt(),
                    NOW);
            letters.put(
                    id,
                    new DeadLetterDetail(
                            edited,
                            current.rawPayload(),
                            dev.pti.common.json.MessageJson.mapper()
                                    .readValue(
                                            payloadJson,
                                            new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}),
                            current.kafka(),
                            current.batchId(),
                            current.modelVersion(),
                            current.triagedAt(),
                            current.triageAttempts(),
                            current.lastReplayAt(),
                            current.resolvedBy(),
                            current.resolvedAt(),
                            List.of(),
                            List.of(),
                            List.of()));
            return Optional.of(new Changed(current.status(), item.source(), item.categoryConfidence()));
        }

        @Override
        public synchronized void log(
                UUID id, String action, String actor, @Nullable BigDecimal confidence, Map<String, Object> details) {
            statements.add("INSERT dlq_action_log " + action);
            DeadLetterDetail letter = letters.get(id);
            actionLog.add(new ActionLogItem(
                    ++actionIds,
                    id,
                    action,
                    actor,
                    confidence,
                    details,
                    NOW.plusMillis(actionIds),
                    letter.item().source(),
                    letter.status()));
        }
    };

    // -------------------------------------------------------------------------------------------- replays

    public final ReplayReader replayReader = new ReplayReader() {
        @Override
        public Page<ReplayRequest> list(ReplayFilter filter, PageRequest page) {
            List<ReplayRequest> rows = replays.values().stream()
                    .filter(replay -> !replay.requestedAt().isBefore(filter.from())
                            && replay.requestedAt().isBefore(filter.to()))
                    .filter(replay -> filter.kind() == null || replay.kind() == filter.kind())
                    .filter(replay ->
                            filter.statuses().isEmpty() || filter.statuses().contains(replay.status()))
                    .filter(replay -> filter.source() == null || filter.source().equals(replay.source()))
                    .filter(replay ->
                            filter.requestedBy() == null || filter.requestedBy().equals(replay.requestedBy()))
                    .toList();
            return InMemoryPaging.page(rows, page, ReplayRequest::requestedAt, ReplayRequest::id);
        }

        @Override
        public Optional<ReplayDetail> find(UUID id) {
            return Optional.ofNullable(replays.get(id)).map(replay -> new ReplayDetail(replay, null));
        }

        @Override
        public ReplayEstimate.History history(String source, Instant from, Instant to) {
            return history;
        }

        @Override
        public boolean rawReplayActive(String source) {
            return activeRaw(source).isPresent();
        }
    };

    private Optional<UUID> activeRaw(String source) {
        return replays.values().stream()
                .filter(replay -> replay.kind() == ReplayKind.RAW_RANGE
                        && replay.source().equals(source)
                        && Set.of("PENDING", "RUNNING").contains(replay.status()))
                .map(ReplayRequest::id)
                .findFirst();
    }

    public final ReplayRequestStore replayStore = new ReplayRequestStore() {
        @Override
        public Optional<ReplayRequest> findByKey(String requestedBy, String idempotencyKey) {
            return replays.values().stream()
                    .filter(replay ->
                            replay.requestedBy().equals(requestedBy) && idempotencyKey.equals(replay.idempotencyKey()))
                    .findFirst();
        }

        @Override
        public synchronized Optional<ReplayRequest> insert(ReplayRequest request) {
            if (request.idempotencyKey() != null
                    && findByKey(request.requestedBy(), request.idempotencyKey())
                            .isPresent()) {
                return Optional.empty();
            }
            if (request.kind() == ReplayKind.RAW_RANGE
                    && activeRaw(request.source()).isPresent()) {
                throw new ActiveReplayConflict(new IllegalStateException("replay_request_one_raw_per_source"));
            }
            if (request.kind() == ReplayKind.DLQ_RECORD
                    && activeRecordReplay(request.deadLetterId()).isPresent()) {
                throw new ActiveReplayConflict(new IllegalStateException("replay_request_one_per_record"));
            }
            statements.add("INSERT replay_request " + request.kind());
            replays.put(request.id(), request);
            return Optional.of(request);
        }

        @Override
        public Optional<ReplayRequest> find(UUID id) {
            return Optional.ofNullable(replays.get(id));
        }

        @Override
        public Optional<UUID> activeRawReplay(String source) {
            return activeRaw(source);
        }

        @Override
        public Optional<UUID> activeRecordReplay(UUID deadLetterId) {
            return replays.values().stream()
                    .filter(replay -> replay.kind() == ReplayKind.DLQ_RECORD
                            && deadLetterId.equals(replay.deadLetterId())
                            && Set.of("PENDING", "RUNNING").contains(replay.status()))
                    .map(ReplayRequest::id)
                    .findFirst();
        }
    };

    // ----------------------------------------------------------------------------------------------- flags

    public final RuntimeFlagReader flagReader = new RuntimeFlagReader() {
        @Override
        public List<RuntimeFlag> list() {
            return new TreeMap<>(flags).values().stream().toList();
        }

        @Override
        public Optional<RuntimeFlag> find(String key) {
            return Optional.ofNullable(flags.get(key));
        }
    };

    public final RuntimeFlagStore flagStore = new RuntimeFlagStore() {
        @Override
        public Optional<RuntimeFlag> find(String key) {
            return Optional.ofNullable(flags.get(key));
        }

        @Override
        public synchronized Optional<RuntimeFlag> update(String key, Object value, String updatedBy) {
            RuntimeFlag current = flags.get(key);
            if (current == null) {
                return Optional.empty();
            }
            statements.add("UPDATE runtime_flag " + key);
            RuntimeFlag updated = new RuntimeFlag(key, value, current.description(), updatedBy, NOW);
            flags.put(key, updated);
            return Optional.of(updated);
        }
    };
}
