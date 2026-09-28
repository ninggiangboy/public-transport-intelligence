package dev.pti.simulator;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The simulator's two databases on pg-source, both as {@code source_simulator} (DOC-29 §3.1): {@code ticketing_source}
 * for the ticketing system of record and {@code pti_sim} for the ledger.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    @ConfigurationProperties("pti.datasource.ticketing")
    DataSourceProperties ticketingDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("pti.datasource.ticketing.hikari")
    HikariDataSource ticketingDataSource(@Qualifier("ticketingDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    JdbcTemplate ticketingJdbcTemplate(@Qualifier("ticketingDataSource") HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConfigurationProperties("pti.datasource.sim")
    DataSourceProperties simDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("pti.datasource.sim.hikari")
    HikariDataSource simDataSource(@Qualifier("simDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    JdbcTemplate simJdbcTemplate(@Qualifier("simDataSource") HikariDataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
