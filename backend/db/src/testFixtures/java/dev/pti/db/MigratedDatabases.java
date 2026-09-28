package dev.pti.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * pg-warehouse and pg-source, bootstrapped and migrated once per test JVM and shared by the read-mostly
 * test classes. Tests that change data roll back; Ryuk removes the containers when the JVM exits.
 */
public final class MigratedDatabases {

    private static final PostgreSQLContainer WAREHOUSE = PostgresFixtures.warehouse();
    private static final PostgreSQLContainer SOURCE = PostgresFixtures.source();
    private static boolean started;

    private MigratedDatabases() {}

    public static synchronized void start() {
        if (started) {
            return;
        }
        WAREHOUSE.start();
        SOURCE.start();
        int exitCode = DbMigrate.run(PostgresFixtures.migrateEnv(WAREHOUSE, SOURCE));
        if (exitCode != DbMigrate.EXIT_OK) {
            throw new IllegalStateException("db-migrate exited with " + exitCode);
        }
        started = true;
    }

    /** Connects to one of pti_warehouse, ticketing_source or pti_sim as a role created by the bootstrap. */
    public static Connection connect(String database, String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), role, password(role));
    }

    /** The JDBC URL of pti_warehouse, ticketing_source or pti_sim, starting the databases if needed. */
    public static String jdbcUrl(String database) {
        start();
        PostgreSQLContainer container = "pti_warehouse".equals(database) ? WAREHOUSE : SOURCE;
        return PostgresFixtures.jdbcUrl(container, database);
    }

    /** The test password of a role created by the bootstrap scripts. */
    public static String password(String role) {
        String password = PostgresFixtures.PASSWORDS.get(role.toUpperCase(Locale.ROOT) + "_PASSWORD");
        if (password == null) {
            throw new IllegalArgumentException("No bootstrap password for role " + role);
        }
        return password;
    }
}
