package dev.pti.analytics.alert.config;

import dev.pti.analytics.alert.adapter.out.jdbc.JdbcAlertWriter;
import dev.pti.analytics.alert.application.port.AlertWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The alert writer of DOC-23 §10.2. */
@Configuration(proxyBeanMethods = false)
class AlertConfiguration {

    @Bean
    AlertWriter alertWriter(JdbcClient jdbc) {
        return new JdbcAlertWriter(jdbc);
    }
}
