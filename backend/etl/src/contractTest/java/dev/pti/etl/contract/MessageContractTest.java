package dev.pti.etl.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import dev.pti.common.error.DataException;
import dev.pti.common.json.MessageJson;
import dev.pti.common.message.MessageSchemas;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.core.MessageProcessor;
import dev.pti.etl.core.WriteSet;
import dev.pti.etl.core.cdc.CdcReader;
import dev.pti.etl.core.cdc.SalePointCdcProcessor;
import dev.pti.etl.core.cdc.TicketSaleCdcProcessor;
import dev.pti.etl.core.gtfsrt.EnvelopeReader;
import dev.pti.etl.core.gtfsrt.TripUpdateProcessor;
import dev.pti.etl.core.gtfsrt.VehiclePositionProcessor;
import dev.pti.etl.rules.RealtimeRules;
import dev.pti.etl.rules.RuleEngine;
import dev.pti.etl.rules.TicketRules;
import dev.pti.etl.testing.EtlFixtures;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

/**
 * DOC-44 §9.1, consumer side: every contract example of {@code common} maps to its expected write set, and every
 * invalid one is rejected at the expected stage and rule. With {@code -Dpti.contract.write-expected=true} a missing
 * expectation is written next to the example (under {@code pti.repo-root}) for review.
 */
class MessageContractTest {

    private static final Validator VALIDATOR =
            Validation.buildDefaultValidatorFactory().getValidator();
    private static final DqProperties DQ = EtlFixtures.dq();
    private static final EnvelopeReader ENVELOPES = new EnvelopeReader(MessageSchemas.load(), VALIDATOR);
    private static final CdcReader CDC = new CdcReader(VALIDATOR);
    private static final Map<String, MessageProcessor> PROCESSORS = Map.of(
            "vehicle-position",
            new VehiclePositionProcessor(ENVELOPES, new RuleEngine<>(RealtimeRules.all(DQ), DQ)),
            "trip-update",
            new TripUpdateProcessor(ENVELOPES, new RuleEngine<>(RealtimeRules.all(DQ), DQ)),
            "ticket-sale",
            new TicketSaleCdcProcessor(CDC, new RuleEngine<>(TicketRules.all(DQ), DQ), EtlFixtures.AGENCY_ZONE),
            "sale-point",
            new SalePointCdcProcessor(CDC));

    private static Stream<Arguments> examples(String kind) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:contract-examples/" + kind + "/*/*.json");
        return Stream.of(resources)
                .filter(r -> !String.valueOf(r.getFilename()).endsWith(".expected.json"))
                .map(r -> Arguments.of(folder(r) + "/" + r.getFilename(), r));
    }

    static Stream<Arguments> valid() throws IOException {
        return examples("valid");
    }

    static Stream<Arguments> invalid() throws IOException {
        return examples("invalid");
    }

    private static String folder(Resource resource) {
        try {
            String path = resource.getURL().getPath();
            String parent = path.substring(0, path.lastIndexOf('/'));
            return parent.substring(parent.lastIndexOf('/') + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] bytes(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InboundMessage message(MessageProcessor processor, byte[] value) {
        EtlSource source = processor.source();
        return new InboundMessage(source, "key", value, source.requireTopic(), 0, 1L, EtlFixtures.NOW, Map.of());
    }

    private static JsonNode expected(Resource example) {
        try {
            Resource sidecar =
                    example.createRelative(String.valueOf(example.getFilename()).replace(".json", ".expected.json"));
            return sidecar.exists() ? MessageJson.mapper().readTree(bytes(sidecar)) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What a message writes, without the message itself; stable enough to commit as an expectation. */
    static JsonNode snapshot(WriteSet set) {
        Map<String, @Nullable Object> view = new LinkedHashMap<>();
        view.put("business_key", set.businessKey());
        view.put("event_timestamp", set.eventTimestamp().toString());
        view.put("vehicle_positions", set.vehiclePositions());
        view.put("trip_updates", set.tripUpdates());
        view.put("ticket_sales", set.ticketSales());
        view.put("sale_points", set.salePoints());
        view.put("vehicle_ids", set.vehicleIds());
        // Through text, so numbers get the node types a file read gives them.
        return MessageJson.mapper().readTree(MessageJson.mapper().writeValueAsString(view));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valid")
    void aValidExampleMapsToItsExpectedWriteSet(String name, Resource example) {
        MessageProcessor processor = PROCESSORS.get(name.substring(0, name.indexOf('/')));
        WriteSet set = processor.process(message(processor, bytes(example)), EtlFixtures.context());
        JsonNode actual = snapshot(set);
        JsonNode expected = expected(example);
        if (expected == null) {
            writeExpectation(name, actual);
            fail("No expectation for " + name + "; actual write set: " + actual.toPrettyString());
        }
        assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalid")
    void anInvalidExampleIsRejectedAtTheExpectedStage(String name, Resource example) {
        MessageProcessor processor = PROCESSORS.get(name.substring(0, name.indexOf('/')));
        JsonNode expected = expected(example);
        assertThat(expected).as("expectation of " + name).isNotNull();
        try {
            WriteSet set = processor.process(message(processor, bytes(example)), EtlFixtures.context());
            fail(name + " was accepted: " + snapshot(set));
        } catch (DataException e) {
            assertThat(e.stage().name())
                    .as(e.getMessage())
                    .isEqualTo(expected.path("stage").asString());
            JsonNode rule = expected.path("rule_id");
            assertThat(e.ruleId()).as(e.getMessage()).isEqualTo(rule.isNull() ? null : rule.asString());
        }
    }

    private static void writeExpectation(String name, JsonNode actual) {
        if (!Boolean.getBoolean("pti.contract.write-expected")) {
            return;
        }
        Path file = Path.of(
                System.getProperty("pti.repo-root", "."),
                "backend/common/src/testFixtures/resources/contract-examples/valid",
                name.replace(".json", ".expected.json"));
        try {
            Files.writeString(file, actual.toPrettyString() + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
