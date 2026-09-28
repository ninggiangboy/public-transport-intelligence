package dev.pti.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * pg-warehouse and pg-source as compose runs them: the image pinned in deploy/versions.env and the
 * bootstrap scripts from deploy/compose/postgres (DOC-17 §3.1).
 */
public final class PostgresFixtures {

    /** Test-only passwords for every role the bootstrap scripts create. */
    public static final Map<String, String> PASSWORDS = Map.ofEntries(
            Map.entry("PTI_OWNER_PASSWORD", "pti_owner_pw"),
            Map.entry("ETL_WRITER_PASSWORD", "etl_writer_pw"),
            Map.entry("TRIAGE_WRITER_PASSWORD", "triage_writer_pw"),
            Map.entry("API_READER_PASSWORD", "api_reader_pw"),
            Map.entry("REPLAY_OPERATOR_PASSWORD", "replay_operator_pw"),
            Map.entry("EXPERIMENT_RUNNER_PASSWORD", "experiment_runner_pw"),
            Map.entry("TICKETING_OWNER_PASSWORD", "ticketing_owner_pw"),
            Map.entry("SIM_OWNER_PASSWORD", "sim_owner_pw"),
            Map.entry("SOURCE_SIMULATOR_PASSWORD", "source_simulator_pw"),
            Map.entry("DEBEZIUM_PASSWORD", "debezium_pw"));

    private PostgresFixtures() {}

    public static PostgreSQLContainer warehouse() {
        return container("warehouse", List.of("postgres", "-c", "fsync=off", "-c", "timezone=UTC"));
    }

    public static PostgreSQLContainer source() {
        return container(
                "source", List.of("postgres", "-c", "fsync=off", "-c", "timezone=UTC", "-c", "wal_level=logical"));
    }

    public static String jdbcUrl(PostgreSQLContainer container, String database) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(container.getHost(), container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
    }

    /** The environment db-migrate sees when pointed at the two containers. */
    public static Map<String, String> migrateEnv(PostgreSQLContainer warehouse, PostgreSQLContainer source) {
        Map<String, String> env = new HashMap<>(PASSWORDS);
        env.put(MigrationSet.WAREHOUSE.urlVariable(), jdbcUrl(warehouse, "pti_warehouse"));
        env.put(MigrationSet.TICKETING.urlVariable(), jdbcUrl(source, "ticketing_source"));
        env.put(MigrationSet.SIM.urlVariable(), jdbcUrl(source, "pti_sim"));
        return env;
    }

    private static PostgreSQLContainer container(String name, List<String> command) {
        Path bootstrap = repoRoot().resolve("deploy/compose/postgres/" + name + "/10-bootstrap.sh");
        PostgreSQLContainer container = new PostgreSQLContainer(postgresImage())
                .withCopyFileToContainer(
                        MountableFile.forHostPath(bootstrap, 0755), "/docker-entrypoint-initdb.d/10-bootstrap.sh");
        container.withEnv(PASSWORDS);
        container.setCommand(command.toArray(String[]::new));
        return container;
    }

    private static DockerImageName postgresImage() {
        Path versions = repoRoot().resolve("deploy/versions.env");
        try {
            String image = Files.readAllLines(versions).stream()
                    .filter(line -> line.startsWith("POSTGRES_IMAGE="))
                    .map(line -> line.substring("POSTGRES_IMAGE=".length()).strip())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("POSTGRES_IMAGE is missing from " + versions));
            return DockerImageName.parse(image).asCompatibleSubstituteFor("postgres");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("pti.repo-root"));
    }
}
