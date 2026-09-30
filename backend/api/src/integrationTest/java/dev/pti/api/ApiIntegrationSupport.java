package dev.pti.api;

import dev.pti.api.testing.JwtFixture;
import dev.pti.db.MigratedDatabases;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The API against a real pg-warehouse: the container of the {@code db} fixtures, bootstrapped with the roles of
 * DOC-17 and migrated by the real Flyway migrations, and the application connecting as {@code api_reader} and
 * {@code replay_operator} exactly as in compose. The {@code static-jwt} profile lets the tests sign their own tokens.
 *
 * <p>The active feed cache lives 1 s and the probe runs every 200 ms, so the tests can change the data and wait for
 * the API to notice. The container is shared by the test classes of the JVM: each class cleans what it touches.
 */
@SpringBootTest(
        properties = {
            "pti.observability.freshness-probe.interval=200ms",
            "pti.api.freshness.insight-interval=1s",
            "pti.api.cache.active-feed.ttl=1s",
            "pti.api.rate-limit.enabled=false",
            "management.server.port=-1"
        })
@AutoConfigureMockMvc
@ActiveProfiles("static-jwt")
public abstract class ApiIntegrationSupport {

    private static final Path PUBLIC_KEY = tempFile("public-key", ".pem");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = MigratedDatabases.jdbcUrl("pti_warehouse");
        registry.add("pti.datasource.reader.url", () -> url);
        registry.add("pti.datasource.reader.username", () -> "api_reader");
        registry.add("pti.datasource.reader.password", () -> MigratedDatabases.password("api_reader"));
        registry.add("pti.datasource.operator.url", () -> url);
        registry.add("pti.datasource.operator.username", () -> "replay_operator");
        registry.add("pti.datasource.operator.password", () -> MigratedDatabases.password("replay_operator"));
        registry.add(
                "pti.api.security.static-jwt.public-key-file",
                () -> JwtFixture.writePublicKey(PUBLIC_KEY).toString());
    }

    /** Runs statements as the owner of the schema, which is how the tests put data where the API reads it. */
    protected static void asOwner(String... statements) {
        try (Connection connection = MigratedDatabases.connect("pti_warehouse", "pti_owner");
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot run " + String.join("; ", statements), e);
        }
    }

    /** The ACTIVE feed of DOC-31 §10.2, with a hash made of one character so that each test can have its own. */
    protected static String activeFeedSql(String hashCharacter, String publisherVersion) {
        return """
                INSERT INTO dw.gtfs_feed_version (feed_hash, source_uri, raw_object_key, publisher_feed_version,
                  agency_timezone, valid_from, valid_to, bbox_min_lon, bbox_min_lat, bbox_max_lon, bbox_max_lat,
                  status, activated_at)
                VALUES (repeat('%s', 64), 'file:///feed.zip', 'raw/gtfs-static/%s.zip', '%s', 'America/Chicago',
                  DATE '2026-08-23', DATE '2026-12-12', -94.0, 44.0, -93.0, 45.0, 'ACTIVE',
                  TIMESTAMPTZ '2026-09-27 08:34:40Z')""".formatted(hashCharacter, hashCharacter, publisherVersion);
    }

    private static Path tempFile(String prefix, String suffix) {
        try {
            Path file = Files.createTempFile(prefix, suffix);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
