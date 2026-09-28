package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DbMigrateIT {

    @Container
    static final PostgreSQLContainer WAREHOUSE = PostgresFixtures.warehouse();

    @Container
    static final PostgreSQLContainer SOURCE = PostgresFixtures.source();

    @Test
    @Order(1)
    void firstRunAppliesEverySetOnEmptyDatabases() throws SQLException {
        assertThat(DbMigrate.run(PostgresFixtures.migrateEnv(WAREHOUSE, SOURCE)))
                .isEqualTo(DbMigrate.EXIT_OK);

        assertThat(history(MigrationSet.WAREHOUSE, "pti_warehouse"))
                .containsExactly("1", "2", "3", "4", "5.1", "5.2", "6", "R:grants");
        assertThat(history(MigrationSet.TICKETING, "ticketing_source")).containsExactly("1", "R:grants");
        assertThat(history(MigrationSet.SIM, "pti_sim")).containsExactly("1", "R:grants");
    }

    @Test
    @Order(2)
    void secondRunIsANoOp() throws SQLException {
        List<String> before = history(MigrationSet.WAREHOUSE, "pti_warehouse");

        assertThat(DbMigrate.run(PostgresFixtures.migrateEnv(WAREHOUSE, SOURCE)))
                .isEqualTo(DbMigrate.EXIT_OK);

        assertThat(history(MigrationSet.WAREHOUSE, "pti_warehouse")).isEqualTo(before);
        assertThat(history(MigrationSet.TICKETING, "ticketing_source")).containsExactly("1", "R:grants");
        assertThat(history(MigrationSet.SIM, "pti_sim")).containsExactly("1", "R:grants");
    }

    @Test
    @Order(3)
    void springBatchTablesLiveInTheBatchSchema() throws SQLException {
        try (Connection c = connect(MigrationSet.WAREHOUSE, "pti_warehouse");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT count(*) FROM pg_tables
                        WHERE schemaname = 'batch' AND tablename LIKE 'batch\\_%'""")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(6);
        }
    }

    @Test
    @Order(4)
    void wrongPasswordFailsWithoutTouchingTheNextSets() {
        Map<String, String> env = new HashMap<>(PostgresFixtures.migrateEnv(WAREHOUSE, SOURCE));
        env.put("PTI_OWNER_PASSWORD", "wrong");

        assertThat(DbMigrate.run(env)).isEqualTo(DbMigrate.EXIT_MIGRATION_FAILED);
    }

    /** Successful history rows in install order: versions, then "R:<description>" for repeatables. */
    private static List<String> history(MigrationSet set, String database) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(set, database);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT coalesce(version, 'R:' || description), success
                        FROM flyway_schema_history ORDER BY installed_rank""")) {
            while (rs.next()) {
                assertThat(rs.getBoolean(2)).isTrue();
                rows.add(rs.getString(1));
            }
        }
        return rows;
    }

    private static Connection connect(MigrationSet set, String database) throws SQLException {
        PostgreSQLContainer container = set == MigrationSet.WAREHOUSE ? WAREHOUSE : SOURCE;
        return DriverManager.getConnection(
                PostgresFixtures.jdbcUrl(container, database),
                set.owner(),
                PostgresFixtures.PASSWORDS.get(set.passwordVariable()));
    }
}
