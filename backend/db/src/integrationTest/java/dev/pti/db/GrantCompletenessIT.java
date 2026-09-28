package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DOC-17 §7.3: catalog checks that catch a new object without grants, or a runtime role with too much power. */
class GrantCompletenessIT {

    private static final String[] WAREHOUSE_RUNTIME_ROLES = {
        "etl_writer", "triage_writer", "api_reader", "replay_operator", "experiment_runner"
    };

    /** Tables and views of dw, ops and insight that no runtime role can read or write (partitions excluded). */
    @Test
    void everyWarehouseRelationIsGrantedToARuntimeRole() throws SQLException {
        List<String> ungranted = query("pti_warehouse", "pti_owner", """
                SELECT n.nspname || '.' || c.relname
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN ('dw', 'ops', 'insight')
                  AND c.relkind IN ('r', 'p', 'v', 'm')
                  AND NOT c.relispartition
                  AND NOT EXISTS (
                    SELECT 1 FROM unnest(?::text[]) AS r(role)
                    WHERE has_any_column_privilege(r.role, c.oid, 'SELECT, INSERT, UPDATE'))
                ORDER BY 1""", WAREHOUSE_RUNTIME_ROLES);

        assertThat(ungranted).as("relations missing from R__grants.sql").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "pti_warehouse    | pti_owner       | etl_writer,triage_writer,api_reader,replay_operator,experiment_runner",
                "ticketing_source | ticketing_owner | source_simulator,debezium,experiment_runner",
                "pti_sim          | sim_owner       | source_simulator,experiment_runner"
            })
    void runtimeRolesHaveNoExcessPrivileges(String database, String owner, String roles) throws SQLException {
        String[] runtimeRoles = roles.split(",");

        List<String> violations = query(database, owner, """
                WITH r(role) AS (SELECT unnest(?::text[])),
                rel AS (
                  SELECT c.oid, c.relkind IN ('r', 'p', 'v', 'm', 'f') AS is_table, c.relowner,
                         n.nspname || '.' || c.relname AS name, n.nspname
                  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                  WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\\_%'),
                schema AS (
                  SELECT oid, nspname, nspowner FROM pg_namespace
                  WHERE nspname NOT IN ('pg_catalog', 'information_schema') AND nspname NOT LIKE 'pg\\_%')
                SELECT r.role || ' has TRUNCATE on ' || rel.name
                FROM r CROSS JOIN rel
                WHERE rel.is_table AND rel.nspname <> 'exp' AND has_table_privilege(r.role, rel.oid, 'TRUNCATE')
                UNION ALL
                SELECT r.role || ' has TRIGGER on ' || rel.name
                FROM r CROSS JOIN rel
                WHERE rel.is_table AND has_table_privilege(r.role, rel.oid, 'TRIGGER')
                UNION ALL
                SELECT r.role || ' has REFERENCES on ' || rel.name
                FROM r CROSS JOIN rel
                WHERE rel.is_table AND has_any_column_privilege(r.role, rel.oid, 'REFERENCES')
                UNION ALL
                SELECT r.role || ' has CREATE on schema ' || schema.nspname
                FROM r CROSS JOIN schema
                WHERE has_schema_privilege(r.role, schema.oid, 'CREATE')
                UNION ALL
                SELECT r.role || ' owns schema ' || schema.nspname
                FROM r JOIN schema ON schema.nspowner = r.role::regrole
                UNION ALL
                SELECT r.role || ' owns ' || rel.name
                FROM r JOIN rel ON rel.relowner = r.role::regrole
                UNION ALL
                SELECT r.role || ' owns function ' || p.oid::regprocedure
                FROM r JOIN pg_proc p ON p.proowner = r.role::regrole
                UNION ALL
                SELECT r.role || ' owns type ' || t.oid::regtype
                FROM r JOIN pg_type t ON t.typowner = r.role::regrole
                ORDER BY 1""", runtimeRoles);

        assertThat(violations).isEmpty();
    }

    private static List<String> query(String database, String role, String sql, String[] roles) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = MigratedDatabases.connect(database, role);
                PreparedStatement statement = c.prepareStatement(sql)) {
            Array array = c.createArrayOf("text", roles);
            statement.setArray(1, array);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
        }
        return rows;
    }
}
