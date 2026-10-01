package dev.pti.api.sim.domain;

import dev.pti.api.platform.domain.ApiException;
import dev.pti.api.platform.domain.ProblemType;

/** The simulator did not answer in time or at all (502 {@code simulator-unavailable}, DOC-32 E-90). */
public class SimulatorUnavailableException extends ApiException {

    private static final long serialVersionUID = 1L;

    public SimulatorUnavailableException(Throwable cause) {
        super("The simulator did not answer.", cause);
    }

    @Override
    public ProblemType type() {
        return ProblemType.SIMULATOR_UNAVAILABLE;
    }
}
