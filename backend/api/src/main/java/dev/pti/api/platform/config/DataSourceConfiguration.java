package dev.pti.api.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.pti.common.spring.SpringTransactionRunner;
import dev.pti.common.tx.TransactionRunner;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;

/**
 * The two datasources of the API (DR-20, DOC-31 §10.1). {@code reader} ({@code api_reader}, primary) serves every
 * {@code GET}; {@code operator} ({@code replay_operator}) serves every write and the read-back of what was written.
 * A repository is tied to one of them through its constructor ({@code @Qualifier("reader")} or
 * {@code @Qualifier("operator")}, the latter only for {@code @OperatorRepository} classes, rule A-03); there is no
 * routing datasource. Each has its own transaction manager and {@code TransactionRunner}: {@code readerTx} is
 * read-only, {@code operatorTx} is READ COMMITTED, and no transaction spans both.
 */
@Configuration(proxyBeanMethods = false)
class DataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("pti.datasource.reader")
    DataSourceProperties readerDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    @ConfigurationProperties("pti.datasource.reader.hikari")
    HikariDataSource readerDataSource(@Qualifier("readerDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @ConfigurationProperties("pti.datasource.operator")
    DataSourceProperties operatorDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("pti.datasource.operator.hikari")
    HikariDataSource operatorDataSource(@Qualifier("operatorDataSourceProperties") DataSourceProperties properties) {
        return properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean("reader")
    JdbcClient readerJdbc(@Qualifier("readerDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean("operator")
    JdbcClient operatorJdbc(@Qualifier("operatorDataSource") DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    PlatformTransactionManager readerTransactionManager(@Qualifier("readerDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    PlatformTransactionManager operatorTransactionManager(@Qualifier("operatorDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean("readerTx")
    TransactionRunner readerTx(@Qualifier("readerTransactionManager") PlatformTransactionManager manager) {
        return new SpringTransactionRunner(manager).readOnly();
    }

    @Bean("operatorTx")
    TransactionRunner operatorTx(@Qualifier("operatorTransactionManager") PlatformTransactionManager manager) {
        return new SpringTransactionRunner(manager).isolation(Isolation.READ_COMMITTED);
    }
}
