package dev.pti.simulator.scenario;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.SimProperties;
import dev.pti.simulator.TickLoop;
import dev.pti.simulator.emit.Emitter;
import dev.pti.simulator.emit.ResendQueue;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.ticketing.SalePointCatalog;
import dev.pti.simulator.ticketing.SalePointSuggestions;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Wires the scenario engine and the scenarios of DOC-25 §7, in catalog order. */
@Configuration(proxyBeanMethods = false)
public class ScenarioConfiguration {

    /** How often the engine ends runs whose time is up and advances load ramps. */
    static final Duration TICK = Duration.ofMillis(500);

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
            Validator validator,
            SimProperties properties,
            MeterRegistry registry) {
        List<Scenario<?>> scenarios = List.of(
                new BunchingScenario(feed, emitter),
                new DisruptionScenario(feed),
                new BadDataScenario(),
                new DuplicatesScenario(resends, clock),
                new TicketSpikeScenario(catalog),
                new RefundBurstScenario(catalog),
                new LoadRampScenario(rate));
        return new ScenarioEngine(
                scenarios,
                hooks,
                new ParamBinder(mapper, validator),
                new JdbcScenarioRuns(jdbc, mapper),
                clock,
                mapper,
                properties.seed(),
                properties.scenario().maxDuration(),
                registry);
    }

    @Bean
    ScenarioCatalog scenarioCatalog(
            JsonMapper mapper,
            SimProperties properties,
            SalePointCatalog catalog,
            @Qualifier("ticketingJdbcTemplate") JdbcTemplate ticketing,
            BusinessClock clock) {
        return new ScenarioCatalog(
                mapper, properties.scenario().maxDuration(), new SalePointSuggestions(ticketing, catalog, clock));
    }

    @Bean
    TickLoop scenarioLoop(ScenarioEngine engine) {
        return new TickLoop("sim-scenarios", engine::tick, TICK);
    }
}
