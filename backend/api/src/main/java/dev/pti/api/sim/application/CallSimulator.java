package dev.pti.api.sim.application;

import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.api.sim.application.port.SimulatorGateway;
import dev.pti.api.sim.domain.SimulatorCall;
import dev.pti.api.sim.domain.SimulatorEndpoints;
import dev.pti.api.sim.domain.SimulatorReply;
import org.jspecify.annotations.Nullable;

/**
 * {@code /sim/**} (DOC-32 E-90): forwards a call of an operator to the control API of the simulator. Only the
 * endpoints of DOC-25 §8 are forwarded; the answer comes back as it is, with a {@code Location} written as the API's
 * own. The call carries who asked ({@code X-Requested-By}), not the token.
 */
public final class CallSimulator {

    private final SimulatorGateway gateway;
    private final String simulatorBaseUrl;

    public CallSimulator(SimulatorGateway gateway, String simulatorBaseUrl) {
        this.gateway = gateway;
        this.simulatorBaseUrl = simulatorBaseUrl;
    }

    /**
     * @param simulatorPath the path under {@code /api/v1}, for example {@code /sim/status}
     * @throws NotFoundException when the simulator has no such endpoint
     */
    public SimulatorReply execute(
            Caller caller,
            String method,
            String simulatorPath,
            @Nullable String query,
            @Nullable String contentType,
            byte[] body) {
        if (!SimulatorEndpoints.allows(method, simulatorPath)) {
            throw new NotFoundException("The simulator has no such endpoint.");
        }
        SimulatorReply reply =
                gateway.exchange(new SimulatorCall(method, simulatorPath, query, contentType, body, caller.actor()));
        String location = reply.location();
        return location == null
                ? reply
                : reply.withLocation(SimulatorEndpoints.publicLocation(location, simulatorBaseUrl));
    }
}
