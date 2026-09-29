package dev.pti.simulator.scenario;

import java.util.List;

/** A scenario request the API answers with a Problem Details error (DOC-25 §8, DOC-30 §3). */
public class ScenarioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final Problem problem;
    private final transient List<FieldError> errors;

    public ScenarioException(Problem problem, String detail) {
        this(problem, detail, List.of());
    }

    public ScenarioException(Problem problem, String detail, List<FieldError> errors) {
        super(detail);
        this.problem = problem;
        this.errors = List.copyOf(errors);
    }

    public static ScenarioException invalid(String field, String message) {
        return new ScenarioException(
                Problem.INVALID_PARAM, "A scenario parameter is invalid.", List.of(new FieldError(field, message)));
    }

    public Problem problem() {
        return problem;
    }

    public List<FieldError> errors() {
        return errors;
    }

    /** @param field the camelCase parameter name */
    public record FieldError(String field, String message) {}

    /** The problem types of the scenario API, with their HTTP status and English title. */
    public enum Problem {
        INVALID_PARAM(400, "invalid-param", "Invalid parameter"),
        UNKNOWN_SCENARIO(404, "unknown-scenario", "Unknown scenario"),
        SCENARIO_RUN_NOT_FOUND(404, "scenario-run-not-found", "Scenario run not found"),
        SCENARIO_CONFLICT(409, "scenario-conflict", "Scenario conflict"),
        NO_ELIGIBLE_VEHICLES(409, "no-eligible-vehicles", "No eligible vehicles"),
        LOAD_RAMP_RUNNING(409, "load-ramp-running", "Load ramp running");

        private final int status;
        private final String slug;
        private final String title;

        Problem(int status, String slug, String title) {
            this.status = status;
            this.slug = slug;
            this.title = title;
        }

        public int status() {
            return status;
        }

        public String slug() {
            return slug;
        }

        public String title() {
            return title;
        }
    }
}
