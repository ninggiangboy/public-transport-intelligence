package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * One row of the DOC-17 §7 permission matrix. The statement runs as {@code role} inside a transaction that is
 * always rolled back. It must either complete (ALLOWED) or fail with 42501 insufficient_privilege (DENIED); any
 * other error means the case itself is wrong and fails the test.
 *
 * @param sql null for a case that only opens a connection
 */
record GrantCase(int number, String database, String role, String operation, Expectation expected, String sql) {

    enum Expectation {
        ALLOWED,
        DENIED
    }

    static final String INSUFFICIENT_PRIVILEGE = "42501";

    static GrantCase allowed(int number, String database, String role, String operation, String sql) {
        return new GrantCase(number, database, role, operation, Expectation.ALLOWED, sql);
    }

    static GrantCase denied(int number, String database, String role, String operation, String sql) {
        return new GrantCase(number, database, role, operation, Expectation.DENIED, sql);
    }

    /** A case that only opens a connection (sql is null). */
    static GrantCase connectDenied(int number, String database, String role) {
        return new GrantCase(number, database, role, "connect", Expectation.DENIED, null);
    }

    void verify() throws SQLException {
        Expectation actual = run();
        assertThat(actual).as("#%d %s: %s", number, role, operation).isEqualTo(expected);
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
