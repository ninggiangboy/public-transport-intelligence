package dev.pti.simulator.control;

import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.scenario.ScenarioEngine;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The internal control API (DOC-25 §8). No authentication: compose binds it to 127.0.0.1 only. */
@RestController
@RequestMapping("/sim")
public class SimController {

    static final String OUT_OF_RANGE = "must be 0 or between 0.1 and 20";

    private final StatusService status;
    private final RateControl rate;
    private final ScenarioEngine scenarios;

    public SimController(StatusService status, RateControl rate, ScenarioEngine scenarios) {
        this.status = status;
        this.rate = rate;
        this.scenarios = scenarios;
    }

    @GetMapping("/status")
    public SimStatus status() {
        return status.status();
    }

    /** Sets one or both multipliers (DOC-25 §6.5), unless a load ramp owns them. Returns the status after. */
    @PutMapping("/rate")
    public SimStatus rate(@RequestBody(required = false) @Nullable RateRequest request) {
        if (request == null || (request.gtfsRt() == null && request.ticketing() == null)) {
            throw new InvalidParamException("Set gtfsRt, ticketing or both.", List.of());
        }
        List<InvalidParamException.FieldError> errors = new ArrayList<>();
        check("gtfsRt", request.gtfsRt(), errors);
        check("ticketing", request.ticketing(), errors);
        if (!errors.isEmpty()) {
            throw new InvalidParamException("A rate multiplier is out of range.", errors);
        }
        if (scenarios.loadRampRunning()) {
            throw new LoadRampRunningException();
        }
        rate.set(request.gtfsRt(), request.ticketing());
        return status.status();
    }

    private static void check(String field, @Nullable Double value, List<InvalidParamException.FieldError> errors) {
        if (value != null && !RateControl.allowed(value)) {
            errors.add(new InvalidParamException.FieldError(field, OUT_OF_RANGE));
        }
    }
}
