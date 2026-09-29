package dev.pti.simulator;

import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Keeps noise out of the traces (DOC-28 §5.1): no observation for Prometheus scrapes and health probes on the
 * actuator, and none for scheduled pollers that usually find nothing to do. Their timers go too; no dashboard or
 * alert uses them.
 */
@Configuration(proxyBeanMethods = false)
public class ObservationFilterConfiguration {

    @Bean
    ObservationPredicate skipActuatorAndSchedulers() {
        return (name, context) -> {
            if (name.equals("tasks.scheduled.execution")) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext request) {
                HttpServletRequest carrier = request.getCarrier();
                String uri = carrier == null ? null : carrier.getRequestURI();
                return uri == null || !uri.startsWith("/actuator");
            }
            return true;
        };
    }
}
