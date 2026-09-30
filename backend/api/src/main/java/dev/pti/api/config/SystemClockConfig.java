package dev.pti.api.config;

import dev.pti.common.time.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The business clock (DR-67, DOC-25 §3.1): the only place of the API that reads the system clock. */
@Configuration(proxyBeanMethods = false)
class SystemClockConfig {

    @Bean
    BusinessClock businessClock(@Value("${pti.clock.offset:0s}") Duration offset) {
        return new BusinessClock(Clock.systemUTC(), offset);
    }
}
