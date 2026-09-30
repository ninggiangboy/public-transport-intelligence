package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                .containsExactly("1", "2", "3", "4", "5.1", "5.2", "6", "7", "R:grants");
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
    void insightTablesExistAfterV7() throws SQLException {
        try (Connection c = connect(MigrationSet.WAREHOUSE, "pti_warehouse");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT string_agg(tablename, ',' ORDER BY tablename) FROM pg_tables
                        WHERE schemaname = 'insight'""")) {
            rs.next();
            assertThat(rs.getString(1))
                    .isEqualTo("analytics_baseline_snapshot,analytics_bunching_cursor,analytics_bunching_pair_state,"
                            + "analytics_route_baseline,insight_bus_bunching,insight_dispatch_suggestion,"
                            + "insight_eta_prediction,insight_otp_scorecard,insight_service_disruption,"
                            + "insight_ticketing_anomaly");
        }
    }

    /** The repeatable grants revoke everything and grant again, so a re-run must leave the ACLs untouched. */
    @Test
    @Order(5)
    void rerunningTheRepeatableGrantsLeavesPrivilegesUnchanged() throws SQLException, IOException {
        String script;
        try (InputStream in = DbMigrateIT.class.getResourceAsStream("/db/migration/warehouse/R__grants.sql")) {
            script = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> before = privileges();

        try (Connection c = connect(MigrationSet.WAREHOUSE, "pti_warehouse");
                Statement s = c.createStatement()) {
            s.execute(script);
            s.execute(script);
        }

        assertThat(before).isNotEmpty();
        assertThat(privileges()).isEqualTo(before);
    }

    @Test
    @Order(6)
    void wrongPasswordFailsWithoutTouchingTheNextSets() {
        Map<String, String> env = new HashMap<>(PostgresFixtures.migrateEnv(WAREHOUSE, SOURCE));
        env.put("PTI_OWNER_PASSWORD", "wrong");

        assertThat(DbMigrate.run(env)).isEqualTo(DbMigrate.EXIT_MIGRATION_FAILED);
    }

    /** Every table and column privilege of the runtime roles, as sorted "grantee schema.table[.column] privilege". */
    private static List<String> privileges() throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = connect(MigrationSet.WAREHOUSE, "pti_warehouse");
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("""
                        SELECT grantee || ' ' || table_schema || '.' || table_name || ' ' || privilege_type
                        FROM information_schema.role_table_grants
                        WHERE grantee IN ('etl_writer', 'triage_writer', 'api_reader', 'replay_operator',
                                          'experiment_runner')
                        UNION ALL
                        SELECT grantee || ' ' || table_schema || '.' || table_name || '.' || column_name
                               || ' ' || privilege_type
                        FROM information_schema.column_privileges
                        WHERE grantee IN ('etl_writer', 'triage_writer', 'api_reader', 'replay_operator',
                                          'experiment_runner')
                          AND table_schema IN ('ops', 'insight')
                        ORDER BY 1""")) {
            while (rs.next()) {
                rows.add(rs.getString(1));
            }
        }
        return rows;
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
