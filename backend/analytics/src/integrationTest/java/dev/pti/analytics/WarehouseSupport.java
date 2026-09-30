package dev.pti.analytics;

import dev.pti.common.spring.SpringTransactionRunner;
import dev.pti.common.tx.TransactionRunner;
import dev.pti.db.MigratedDatabases;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Isolation;

/**
 * The migrated warehouse as {@code etl_writer}, the role analytics runs as (DOC-23 §1.1). One connection per use, so a
 * test can hold a transaction open on one thread while another tries to take the same lock.
 */
public final class WarehouseSupport {

    public final DriverManagerDataSource dataSource;
    public final JdbcClient jdbc;
    public final TransactionRunner tx;

    public WarehouseSupport() {
        dataSource = new DriverManagerDataSource(
                MigratedDatabases.jdbcUrl("pti_warehouse"), "etl_writer", MigratedDatabases.password("etl_writer"));
        jdbc = JdbcClient.create(dataSource);
        tx = new SpringTransactionRunner(new DataSourceTransactionManager(dataSource))
                .isolation(Isolation.READ_COMMITTED);
    }
}
