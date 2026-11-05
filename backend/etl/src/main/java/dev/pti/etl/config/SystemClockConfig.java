package dev.pti.etl.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The one place in {@code etl} that reads the system clock (A-07, DR-67). Every bean that needs real time receives
 * this {@link Clock}; business logic takes its "now" from {@code BusinessClock} instead.
 */
@Configuration(proxyBeanMethods = false)
public class SystemClockConfig {

    @Bean
    @Primary
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
