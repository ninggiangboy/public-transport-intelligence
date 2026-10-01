package dev.pti.api.sim.application.port;

import dev.pti.api.sim.domain.SimulatorCall;
import dev.pti.api.sim.domain.SimulatorReply;
import dev.pti.api.sim.domain.SimulatorUnavailableException;

/** The simulator's control API over HTTP (DOC-25 §8): connect within 1 second, answer within 5. */
public interface SimulatorGateway {

    /**
     * Sends the call and returns the answer whatever its status: a Problem Details of the simulator is an answer too.
     *
     * @throws SimulatorUnavailableException when the simulator cannot be reached or does not answer in time
     */
    SimulatorReply exchange(SimulatorCall call);
}
