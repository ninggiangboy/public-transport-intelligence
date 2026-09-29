package dev.pti.simulator.scenario;

import com.github.f4b6a3.uuid.UuidCreator;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts, ends and lists scenario runs (DOC-25 §7.1, §8). A run lives in memory while it runs and in
 * {@code sim.sim_scenario_run} for history. It ends as {@code COMPLETED} after its duration, {@code STOPPED} through
 * the API, or {@code FAILED} when one of its hooks throws; rows left {@code RUNNING} by a previous process are marked
 * {@code FAILED} at startup. Thread-safe: starts and stops are serialized.
 */
public final class ScenarioEngine implements SmartLifecycle {

    /** Before the tick loops, so old runs are closed before anything new can start. */
    public static final int PHASE = 500;

    private static final Logger log = LoggerFactory.getLogger(ScenarioEngine.class);

    private static final String DEFAULT_REQUESTER = "cli";

    private static final String EXPERIMENT_PREFIX = "experiment:";

    private static final int MAX_REQUESTER_LENGTH = 200;

    private final Map<String, Scenario<?>> scenarios = new LinkedHashMap<>();
    private final ScenarioHooks hooks;
    private final ParamBinder binder;
    private final ScenarioRuns repository;
    private final BusinessClock clock;
    private final JsonMapper mapper;
    private final long seed;
    private final Duration maxDuration;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();
    /** Failures reported by hooks, ended on the next tick: a hook may fail while its thread holds a lock. */
    private final Queue<Failure> failures = new ConcurrentLinkedQueue<>();

    private volatile boolean running;

    public ScenarioEngine(
            List<Scenario<?>> scenarios,
            ScenarioHooks hooks,
            ParamBinder binder,
            ScenarioRuns repository,
            BusinessClock clock,
            JsonMapper mapper,
            long seed,
            Duration maxDuration,
            MeterRegistry registry) {
        scenarios.forEach(s -> this.scenarios.put(s.name(), s));
        this.hooks = hooks;
        this.binder = binder;
        this.repository = repository;
        this.clock = clock;
        this.mapper = mapper;
        this.seed = seed;
        this.maxDuration = maxDuration;
        hooks.onFailure((runId, error) -> failures.add(new Failure(runId, error)));
        for (String name : this.scenarios.keySet()) {
            Gauge.builder("pti.sim.scenario.active", this, e -> e.count(name))
                    .tag("scenario", name)
                    .register(registry);
        }
    }

    /** The scenarios in catalog order (DOC-25 §7.2–7.8). */
    public List<Scenario<?>> scenarios() {
        return List.copyOf(scenarios.values());
    }

    /**
     * Starts a run (DOC-25 §8 {@code POST /sim/scenarios/{name}}).
     *
     * @param body the parameters; {@code null} for the defaults
     * @param requestedBy {@code X-Requested-By}; {@code cli} when absent
     */
    public ScenarioRun start(String name, @Nullable JsonNode body, @Nullable String requestedBy) {
        return start(scenario(name), body, requester(requestedBy));
    }

    private <P extends Record> ScenarioRun start(Scenario<P> scenario, @Nullable JsonNode body, String requestedBy) {
        P params = binder.bind(scenario, body);
        Duration duration = scenario.duration(params);
        if (duration.compareTo(maxDuration) > 0) {
            // load-ramp has no duration of its own: it follows from the steps (DOC-25 §7.8).
            throw ScenarioException.invalid(
                    hasDuration(scenario) ? "duration" : "stepDuration",
                    "the run must not last longer than " + maxDuration);
        }
        String target = scenario.target(params);
        synchronized (this) {
            checkConflict(scenario, target);
            UUID runId = UuidCreator.getTimeOrderedEpoch();
            Instant startedAt = clock.realNow();
            ScenarioHandle handle =
                    scenario.start(new ScenarioContext(runId, hooks, seed, startedAt.toEpochMilli()), params);
            ScenarioRun run = new ScenarioRun(
                    runId,
                    scenario.name(),
                    RunStatus.RUNNING,
                    mapper.valueToTree(params),
                    requestedBy,
                    startedAt,
                    startedAt.plus(duration),
                    null,
                    null);
            try {
                repository.insert(run, experimentRunId(requestedBy));
            } catch (RuntimeException e) {
                handle.stop();
                hooks.removeAll(runId);
                throw e;
            }
            active.put(runId, new Active(run, scenario, target, handle));
            log.info(
                    "Scenario started: name={} runId={} requestedBy={} params={}",
                    scenario.name(),
                    runId,
                    requestedBy,
                    run.params());
            return run.withProgress(handle.progress());
        }
    }

    private static boolean hasDuration(Scenario<?> scenario) {
        for (RecordComponent component : scenario.paramsType().getRecordComponents()) {
            if (component.getName().equals("duration")) {
                return true;
            }
        }
        return false;
    }

    private void checkConflict(Scenario<?> scenario, @Nullable String target) {
        for (Active a : active.values()) {
            if (!a.scenario().name().equals(scenario.name())) {
                continue;
            }
            if (scenario.concurrency() == Concurrency.SINGLE) {
                throw new ScenarioException(
                        ScenarioException.Problem.SCENARIO_CONFLICT,
                        "Scenario %s is already running (run %s)."
                                .formatted(scenario.name(), a.run().runId()));
            }
            if (target != null && target.equals(a.target())) {
                throw new ScenarioException(
                        ScenarioException.Problem.SCENARIO_CONFLICT,
                        "Scenario %s is already running on %s (run %s)."
                                .formatted(scenario.name(), target, a.run().runId()));
            }
        }
    }

    /** Stops every running run of a scenario (DOC-25 §8 {@code DELETE /sim/scenarios/{name}}). */
    public synchronized void stopScenario(String name) {
        scenario(name);
        for (Active a : List.copyOf(active.values())) {
            if (a.scenario().name().equals(name)) {
                end(a.run().runId(), RunStatus.STOPPED, null);
            }
        }
    }

    /** Stops one run; a run that has already ended is left as it is. */
    public synchronized void stopRun(UUID runId) {
        if (active.containsKey(runId)) {
            end(runId, RunStatus.STOPPED, null);
            return;
        }
        if (repository.find(runId).isEmpty()) {
            throw runNotFound(runId);
        }
    }

    public ScenarioRun run(UUID runId) {
        Active a = active.get(runId);
        if (a != null) {
            return a.run().withProgress(a.handle().progress());
        }
        return repository.find(runId).orElseThrow(() -> runNotFound(runId));
    }

    /** History, newest first; running runs carry their progress. */
    public List<ScenarioRun> runs(@Nullable RunStatus status, int limit) {
        return repository.list(status, limit).stream()
                .map(r -> {
                    Active a = active.get(r.runId());
                    return a == null ? r : r.withProgress(a.handle().progress());
                })
                .toList();
    }

    /** The runs in progress, oldest first. */
    public List<ScenarioRun> running() {
        List<ScenarioRun> runs = new ArrayList<>();
        active.values().forEach(a -> runs.add(a.run()));
        runs.sort(Comparator.comparing(ScenarioRun::startedAt));
        return runs;
    }

    /** Whether a {@code load-ramp} owns the rate multipliers (DOC-25 §7.8). */
    public boolean loadRampRunning() {
        return count(LoadRampScenario.NAME) > 0;
    }

    /** Ends failed runs and runs whose time is up, and lets the others act. Runs on {@code sim-scenarios}. */
    public void tick() {
        for (Failure f = failures.poll(); f != null; f = failures.poll()) {
            synchronized (this) {
                end(f.runId(), RunStatus.FAILED, f.error());
            }
        }
        long now = clock.realNow().toEpochMilli();
        for (Active a : List.copyOf(active.values())) {
            Instant end = a.run().plannedEndAt();
            if (end != null && end.toEpochMilli() <= now) {
                synchronized (this) {
                    end(a.run().runId(), RunStatus.COMPLETED, null);
                }
            } else {
                a.handle().tick(now);
            }
        }
    }

    private void end(UUID runId, RunStatus status, @Nullable Throwable error) {
        Active a = active.remove(runId);
        if (a == null) {
            return;
        }
        try {
            a.handle().stop();
        } catch (RuntimeException e) {
            log.error("Scenario {} run {} did not stop cleanly", a.scenario().name(), runId, e);
            hooks.removeAll(runId);
        }
        if (status == RunStatus.FAILED) {
            hooks.removeAll(runId);
        }
        try {
            repository.finish(runId, status, clock.realNow());
        } catch (DataAccessException e) {
            // The row stays RUNNING and is marked FAILED at the next startup.
            log.warn("Could not record the end of scenario run {}: {}", runId, e.toString());
        }
        if (error != null) {
            log.error("Scenario failed: name={} runId={}", a.scenario().name(), runId, error);
        } else {
            log.info("Scenario ended: name={} runId={} status={}", a.scenario().name(), runId, status);
        }
    }

    private int count(String name) {
        return (int) active.values().stream()
                .filter(a -> a.scenario().name().equals(name))
                .count();
    }

    private Scenario<?> scenario(String name) {
        Scenario<?> scenario = scenarios.get(name);
        if (scenario == null) {
            throw new ScenarioException(
                    ScenarioException.Problem.UNKNOWN_SCENARIO, "There is no scenario named " + name + ".");
        }
        return scenario;
    }

    private static ScenarioException runNotFound(UUID runId) {
        return new ScenarioException(
                ScenarioException.Problem.SCENARIO_RUN_NOT_FOUND, "There is no scenario run " + runId + ".");
    }

    private static String requester(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return DEFAULT_REQUESTER;
        }
        String value = header.strip();
        return value.length() > MAX_REQUESTER_LENGTH ? value.substring(0, MAX_REQUESTER_LENGTH) : value;
    }

    /** {@code EXP-03/20261002T101500Z-r07} from {@code experiment:EXP-03/20261002T101500Z-r07}. */
    static @Nullable String experimentRunId(String requestedBy) {
        return requestedBy.startsWith(EXPERIMENT_PREFIX) && requestedBy.length() > EXPERIMENT_PREFIX.length()
                ? requestedBy.substring(EXPERIMENT_PREFIX.length())
                : null;
    }

    /** Marks the rows a previous process left {@code RUNNING} as {@code FAILED} (DOC-25 §7.1). */
    @Override
    public void start() {
        try {
            int failed = repository.failRunning(clock.realNow());
            if (failed > 0) {
                log.info("Marked {} scenario runs left RUNNING by the previous process as FAILED", failed);
            }
        } catch (DataAccessException e) {
            log.warn("Could not close scenario runs left RUNNING: {}", e.toString());
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    private record Failure(UUID runId, Throwable error) {}

    private record Active(
            ScenarioRun run, Scenario<?> scenario, @Nullable String target, ScenarioHandle handle) {}
}
