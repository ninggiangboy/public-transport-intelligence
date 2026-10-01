package dev.pti.api.sim.config;

import dev.pti.api.sim.adapter.out.http.HttpSimulatorGateway;
import dev.pti.api.sim.application.CallSimulator;
import dev.pti.api.sim.application.port.SimulatorGateway;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Wires the proxy to the simulator (DOC-32 E-90); it exists only in profile {@code demo}. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@EnableConfigurationProperties(SimProperties.class)
class SimConfiguration {

    @Bean
    SimulatorGateway simulatorGateway(SimProperties properties, ObjectProvider<Tracer> tracer) {
        return new HttpSimulatorGateway(properties.baseUrl(), tracer.getIfAvailable());
    }

    @Bean
    CallSimulator callSimulator(SimulatorGateway gateway, SimProperties properties) {
        return new CallSimulator(gateway, properties.baseUrl());
    }
}
