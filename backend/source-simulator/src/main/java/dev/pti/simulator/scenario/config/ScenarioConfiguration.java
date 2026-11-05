package dev.pti.simulator.scenario.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.config.SimProperties;
import dev.pti.simulator.emit.application.Emitter;
import dev.pti.simulator.emit.application.ResendQueue;
import dev.pti.simulator.feed.domain.Feed;
import dev.pti.simulator.rate.application.RateControl;
import dev.pti.simulator.scenario.adapter.out.jdbc.JdbcScenarioRuns;
import dev.pti.simulator.scenario.adapter.out.json.JacksonMessageCorruptor;
import dev.pti.simulator.scenario.adapter.out.metrics.MicrometerScenarioMetrics;
import dev.pti.simulator.scenario.application.BadDataScenario;
import dev.pti.simulator.scenario.application.BunchingScenario;
import dev.pti.simulator.scenario.application.DisruptionScenario;
import dev.pti.simulator.scenario.application.DuplicatesScenario;
import dev.pti.simulator.scenario.application.LoadRampScenario;
import dev.pti.simulator.scenario.application.RefundBurstScenario;
import dev.pti.simulator.scenario.application.ScenarioEngine;
import dev.pti.simulator.scenario.application.ScenarioHooks;
import dev.pti.simulator.scenario.application.TicketSpikeScenario;
import dev.pti.simulator.scenario.application.port.MessageCorruptor;
import dev.pti.simulator.scenario.domain.Scenario;
import dev.pti.simulator.ticketing.domain.SalePointCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Wires the scenario engine and the scenarios of DOC-25 §7, in catalog order. */
@Configuration(proxyBeanMethods = false)
public class ScenarioConfiguration {

    /** How {@code bad-data} rewrites a message (DOC-25 §7.4). */
    @Bean
    MessageCorruptor messageCorruptor() {
        return new JacksonMessageCorruptor();
    }

    @Bean
    ScenarioEngine scenarioEngine(
            Feed feed,
            Emitter emitter,
            ResendQueue resends,
            RateControl rate,
            SalePointCatalog catalog,
            ScenarioHooks hooks,
            @Qualifier("simJdbcTemplate") JdbcTemplate jdbc,
            BusinessClock clock,
            JsonMapper mapper,
            SimProperties properties,
            MeterRegistry registry,
            MessageCorruptor messageCorruptor) {
        List<Scenario<?>> scenarios = List.of(
                new BunchingScenario(feed, emitter, hooks),
                new DisruptionScenario(feed, hooks),
                new BadDataScenario(hooks, messageCorruptor),
                new DuplicatesScenario(resends, clock, hooks),
                new TicketSpikeScenario(catalog, hooks),
                new RefundBurstScenario(catalog, hooks),
                new LoadRampScenario(rate));
        return new ScenarioEngine(
                scenarios,
                hooks,
                new JdbcScenarioRuns(jdbc, mapper),
                clock,
                properties.seed(),
                properties.scenario().maxDuration(),
                new MicrometerScenarioMetrics(registry));
    }
}
