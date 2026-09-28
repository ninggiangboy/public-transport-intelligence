package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One row of the DOC-17 §7 permission matrix. The statement runs as {@code role} inside a transaction that is
 * always rolled back. It must either complete (ALLOWED) or fail with 42501 insufficient_privilege (DENIED); any
 * other error means the case itself is wrong and fails the test.
 *
 * @param sql null for a case that only opens a connection
 * @param requiresTable null, or a table created by a later migration; the case is skipped until it exists
 */
record GrantCase(
        int number,
        String database,
        String role,
        String operation,
        Expectation expected,
        String sql,
        String requiresTable) {

    enum Expectation {
        ALLOWED,
        DENIED
    }

    static final String INSUFFICIENT_PRIVILEGE = "42501";

    static GrantCase allowed(int number, String database, String role, String operation, String sql) {
        return new GrantCase(number, database, role, operation, Expectation.ALLOWED, sql, null);
    }

    static GrantCase denied(int number, String database, String role, String operation, String sql) {
        return new GrantCase(number, database, role, operation, Expectation.DENIED, sql, null);
    }

    /** A case that only opens a connection (sql is null). */
    static GrantCase connectDenied(int number, String database, String role) {
        return new GrantCase(number, database, role, "connect", Expectation.DENIED, null, null);
    }

    GrantCase requires(String table) {
        return new GrantCase(number, database, role, operation, expected, sql, table);
    }

    void verify() throws SQLException {
        if (requiresTable != null) {
            assumeTrue(tableExists(), requiresTable + " does not exist yet");
        }
        Expectation actual = run();
        assertThat(actual).as("#%d %s: %s", number, role, operation).isEqualTo(expected);
    }

    private boolean tableExists() throws SQLException {
        String owner = "pti_warehouse".equals(database)
                ? "pti_owner"
                : "ticketing_source".equals(database) ? "ticketing_owner" : "sim_owner";
        try (Connection connection = MigratedDatabases.connect(database, owner);
                PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, requiresTable);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private Expectation run() {
        try (Connection connection = MigratedDatabases.connect(database, role)) {
            if (sql == null) {
                return Expectation.ALLOWED;
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
                return Expectation.ALLOWED;
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            if (INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
                return Expectation.DENIED;
            }
            return fail("#%d %s: %s failed with %s instead of succeeding or 42501: %s"
                    .formatted(number, role, operation, e.getSQLState(), e.getMessage()));
        }
    }

    @Override
    public String toString() {
        return "#%d %s %s -> %s".formatted(number, role, operation, expected);
    }
}
