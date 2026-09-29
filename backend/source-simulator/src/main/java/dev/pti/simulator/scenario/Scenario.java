package dev.pti.simulator.scenario;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A scenario the simulator can run on request (DOC-25 §7). Parameters are a record whose components carry
 * {@link ScenarioParam} and Bean Validation constraints; {@link #defaults()} fills what the request leaves out.
 *
 * @param <P> the parameter record
 */
public interface Scenario<P extends Record> {

    /** Kebab-case, the path of {@code POST /sim/scenarios/{name}}. */
    String name();

    /** English title for the UI. */
    String title();

    /** English description for the UI. */
    String description();

    Concurrency concurrency();

    Class<P> paramsType();

    /** The defaults; a {@code null} component without {@code nullable} is required. */
    P defaults();

    /** Checks that need the feed or the sale points, after Bean Validation. */
    default List<ScenarioException.FieldError> check(P params) {
        return List.of();
    }

    /** The route or sale point a {@link Concurrency#PER_TARGET} run acts on. */
    default @Nullable String target(P params) {
        return null;
    }

    /** How long a run lasts. */
    Duration duration(P params);

    /**
     * Starts a run and attaches its hooks.
     *
     * @throws ScenarioException when the run cannot start, e.g. {@code no-eligible-vehicles}
     */
    ScenarioHandle start(ScenarioContext context, P params);
}
