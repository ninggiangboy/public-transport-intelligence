package dev.pti.simulator.config;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.control.adapter.in.web.ScenarioCatalog;
import dev.pti.simulator.control.adapter.in.web.ScenarioParamBinder;
import dev.pti.simulator.control.adapter.in.web.ScenarioParamsJson;
import dev.pti.simulator.scenario.adapter.out.jdbc.SalePointSuggestions;
import dev.pti.simulator.ticketing.domain.SalePointCatalog;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Wires the scenario endpoints of the control API (DOC-25 §8). */
@Configuration(proxyBeanMethods = false)
public class ScenarioApiConfiguration {

    /** Scenario parameters are camelCase, unlike the Kafka messages (DOC-39): a plain mapper plus the module. */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(ScenarioParamsJson.module()).build();

    @Bean
    ScenarioParamBinder scenarioParamBinder(Validator validator) {
        return new ScenarioParamBinder(MAPPER, validator);
    }

    @Bean
    ScenarioCatalog scenarioCatalog(
            SimProperties properties,
            SalePointCatalog catalog,
            @Qualifier("ticketingJdbcTemplate") JdbcTemplate ticketing,
            BusinessClock clock) {
        return new ScenarioCatalog(
                MAPPER, properties.scenario().maxDuration(), new SalePointSuggestions(ticketing, catalog, clock));
    }
}
