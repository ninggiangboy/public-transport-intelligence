package dev.pti.spike;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.ulid.UlidCreator;
import com.github.f4b6a3.uuid.UuidCreator;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Libraries that need no Spring context (DOC-11 §2). */
class PlainLibrariesTest {

    @Test
    void jsonSchema202012WithJackson3() {
        var schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                        "{\"type\":\"object\",\"required\":[\"lat\"],\"properties\":{\"lat\":{\"type\":\"number\",\"maximum\":90}}}");
        var mapper = JsonMapper.builder().build();
        assertThat(schema.validate(mapper.readTree("{\"lat\":44.97}"))).isEmpty();
        assertThat(schema.validate(mapper.readTree("{\"lat\":123.4}"))).hasSize(1);
    }

    @Test
    void canonicalJson() throws Exception {
        assertThat(new JsonCanonicalizer("{\"b\":1, \"a\":2.0}").getEncodedString())
                .isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void identifiers() {
        assertThat(UuidCreator.getTimeOrderedEpoch().version()).isEqualTo(7);
        assertThat(UuidCreator.getNameBasedSha1(UuidCreator.getTimeOrderedEpoch(), "x")
                        .version())
                .isEqualTo(5);
        assertThat(UlidCreator.getMonotonicUlid().toString()).hasSize(26);
    }

    @Test
    void bucket4j() {
        var bucket = Bucket.builder()
                .addLimit(l -> l.capacity(2).refillGreedy(2, Duration.ofSeconds(1)))
                .build();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isTrue();
        assertThat(bucket.tryConsume(1)).isFalse();
    }

    @Test
    void mockWebServer() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse.Builder().body("pong").build());
            server.start();
            assertThat(RestClient.create(server.url("/").toString())
                            .get()
                            .retrieve()
                            .body(String.class))
                    .isEqualTo("pong");
        }
    }

    @Test
    void archUnitReadsJava25Bytecode() {
        var classes = new ClassFileImporter().importPackages("dev.pti.spike");
        assertThat(classes).isNotEmpty();
        noClasses()
                .that()
                .resideInAPackage("..batch..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..web..")
                .check(classes);
    }
}
