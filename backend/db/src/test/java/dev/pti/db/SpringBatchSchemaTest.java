package dev.pti.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SpringBatchSchemaTest {

    private static final String HEADER_END = "SET LOCAL search_path TO batch;\n\n";

    @Test
    void bodyIsIdenticalToTheSchemaShippedInSpringBatch() throws IOException {
        String migration = read("db/migration/warehouse/V5_1__spring_batch_schema.sql");
        String upstream = read("org/springframework/batch/core/schema-postgresql.sql");

        assertThat(migration).contains(HEADER_END);
        assertThat(migration.substring(migration.indexOf(HEADER_END) + HEADER_END.length()))
                .isEqualTo(upstream);
    }

    @Test
    void headerNamesTheSpringBatchVersionOnTheClasspath() throws IOException, ClassNotFoundException {
        String version = Class.forName("org.springframework.batch.core.job.Job")
                .getPackage()
                .getImplementationVersion();
        String migration = read("db/migration/warehouse/V5_1__spring_batch_schema.sql");

        assertThat(version).isNotBlank();
        assertThat(migration).containsPattern("spring-batch-core " + Pattern.quote(version) + "\\s");
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = SpringBatchSchemaTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
