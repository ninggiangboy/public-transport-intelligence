package dev.pti.db;

import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the warehouse, ticketing and sim migration sets in order, then exits (ADR-0024, DOC-17 §5).
 * Exit code 0 when every set is up to date, 1 when a migration fails, 2 when the configuration is incomplete.
 */
public final class DbMigrate {

    static final int EXIT_OK = 0;
    static final int EXIT_MIGRATION_FAILED = 1;
    static final int EXIT_BAD_CONFIGURATION = 2;

    private static final Logger log = LoggerFactory.getLogger(DbMigrate.class);

    private DbMigrate() {}

    public static void main(String[] args) {
        System.exit(run(System.getenv()));
    }

    static int run(Map<String, String> env) {
        for (MigrationSet set : MigrationSet.values()) {
            String password = env.get(set.passwordVariable());
            if (password == null || password.isEmpty()) {
                log.error("{} is not set; cannot migrate the {} database", set.passwordVariable(), set.id());
                return EXIT_BAD_CONFIGURATION;
            }
        }
        for (MigrationSet set : MigrationSet.values()) {
            String url = env.getOrDefault(set.urlVariable(), set.defaultUrl());
            if (!migrate(set, url, env.get(set.passwordVariable()))) {
                return EXIT_MIGRATION_FAILED;
            }
        }
        log.info("All migration sets are up to date");
        return EXIT_OK;
    }

    private static boolean migrate(MigrationSet set, String url, String password) {
        log.info("Migrating {} at {} as {}", set.id(), url, set.owner());
        try {
            Flyway flyway = configure(set, url, password).load();
            MigrateResult result = flyway.migrate();
            MigrationVersion current = flyway.info().current() == null
                    ? MigrationVersion.EMPTY
                    : flyway.info().current().getVersion();
            log.info("{}: {} migration(s) applied, schema version {}", set.id(), result.migrationsExecuted, current);
            return true;
        } catch (FlywayException e) {
            log.error("{}: migration failed", set.id(), e);
            return false;
        }
    }

    /** The Flyway settings shared by every set (DOC-17 §5). */
    static FluentConfiguration configure(MigrationSet set, String url, String password) {
        return Flyway.configure()
                .dataSource(url, set.owner(), password)
                .locations(set.location())
                .defaultSchema("public")
                .schemas("public")
                .createSchemas(false)
                .placeholderReplacement(false)
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateOnMigrate(true);
    }
}
