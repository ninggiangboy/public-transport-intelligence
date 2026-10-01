package dev.pti.api.sim.adapter.in.web;

import dev.pti.api.platform.adapter.in.web.ApiPaths;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.sim.application.CallSimulator;
import dev.pti.api.sim.domain.SimulatorReply;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-90 {@code /api/v1/sim/**} (DOC-32 §11): the proxy to the control API of the simulator, only in profile {@code demo}
 * and only for an operator. It is not in the public OpenAPI document. The query is forwarded as the caller wrote it,
 * so the handler takes any parameter.
 */
@RestController
@Profile("demo")
@Hidden
class SimulatorProxyController {

    private final CallSimulator callSimulator;

    SimulatorProxyController(CallSimulator callSimulator) {
        this.callSimulator = callSimulator;
    }

    @RequestMapping(
            value = ApiPaths.V1 + "/sim/**",
            method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.POST, RequestMethod.DELETE})
    ResponseEntity<byte[]> proxy(
            Caller caller, HttpServletRequest request, @RequestParam Map<String, String> ignoredQueryParameters)
            throws IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length() + ApiPaths.V1.length());
        SimulatorReply reply = callSimulator.execute(
                caller,
                request.getMethod(),
                path,
                request.getQueryString(),
                request.getContentType(),
                request.getInputStream().readAllBytes());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(reply.status());
        if (reply.contentType() != null) {
            response.header(HttpHeaders.CONTENT_TYPE, reply.contentType());
        }
        if (reply.location() != null) {
            response.header(HttpHeaders.LOCATION, reply.location());
        }
        return response.body(reply.body());
    }
}
