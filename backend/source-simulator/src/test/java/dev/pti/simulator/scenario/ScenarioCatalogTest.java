package dev.pti.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.simulator.feed.Feeds;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * T-18: the catalog of {@code GET /sim/scenarios} matches what {@code POST} accepts. Every bound it announces is
 * checked against the binder: just inside passes, just outside fails.
 */
class ScenarioCatalogTest {

    private static final Instant START = Instant.parse("2026-09-29T21:20:00Z");

    /** Values that make the required parameters valid, so one parameter at a time can be probed. */
    private static final Map<String, String> REQUIRED = Map.of("routeId", "\"18\"");

    /** Other parameters a probe needs, e.g. {@code maxDelay} may not be below {@code minDelay}. */
    private static final Map<String, Map<String, String>> ALONG_WITH =
            Map.of("minDelay", Map.of("maxDelay", "\"PT2H\""));

    private final ScenarioKit kit = new ScenarioKit(Feeds.mini(), START);
    private final ScenarioCatalog catalog = new ScenarioCatalog(
            ScenarioKit.MAPPER,
            ScenarioKit.MAX_DURATION,
            () -> IntStream.rangeClosed(1, 20).mapToObj("KIOSK-%03d"::formatted).toList());
    private final ParamBinder binder = new ParamBinder(ScenarioKit.MAPPER, ScenarioKit.VALIDATOR);

    @Test
    void listsTheScenariosInDocumentOrder() {
        assertThat(catalog.describe(kit.engine.scenarios()).items())
                .extracting(ScenarioCatalog.Item::name)
                .containsExactly(
                        "bunching",
                        "disruption",
                        "bad-data",
                        "duplicates",
                        "ticket-spike",
                        "refund-burst",
                        "load-ramp");
    }

    @Test
    void everyComponentIsListedInOrder() {
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            List<String> components = Arrays.stream(scenario.paramsType().getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(catalog.describe(scenario).params())
                    .as(scenario.name())
                    .extracting(ScenarioCatalog.Param::name)
                    .isEqualTo(components);
        }
    }

    @Test
    void loadRampHasNoDurationAndEveryOtherDurationIsCapped() {
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            List<ScenarioCatalog.Param> params = catalog.describe(scenario).params();
            if (scenario.name().equals("load-ramp")) {
                assertThat(params).extracting(ScenarioCatalog.Param::name).doesNotContain("duration");
            } else {
                assertThat(params)
                        .filteredOn(p -> p.name().equals("duration"))
                        .singleElement()
                        .satisfies(p -> assertThat(p.max()).isEqualTo(Duration.ofHours(2)));
            }
        }
    }

    @Test
    void defaultsAreWhatAnEmptyRequestGets() {
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            Record bound = bindAll(scenario, body(scenario));
            JsonNode tree = ScenarioKit.MAPPER.valueToTree(bound);
            for (ScenarioCatalog.Param p : catalog.describe(scenario).params()) {
                if (p.defaultValue() != null) {
                    assertThat(tree.get(p.name()))
                            .as(scenario.name() + "." + p.name())
                            .isEqualTo(p.defaultValue());
                } else {
                    assertThat(p.required() || p.nullable())
                            .as(scenario.name() + "." + p.name())
                            .isTrue();
                }
            }
        }
    }

    @Test
    void requiredParametersAreRequired() {
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            for (ScenarioCatalog.Param p : catalog.describe(scenario).params()) {
                if (p.required()) {
                    ObjectNode body = body(scenario);
                    body.remove(p.name());
                    assertThatThrownBy(() -> binder.bind(scenario, body))
                            .as(scenario.name() + "." + p.name())
                            .isInstanceOf(ScenarioException.class);
                }
            }
        }
    }

    @Test
    void boundsMatchTheValidation() {
        int probed = 0;
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            for (ScenarioCatalog.Param p : catalog.describe(scenario).params()) {
                String where = scenario.name() + "." + p.name();
                if (p.min() != null) {
                    accepts(scenario, p, value(p, p.min(), 0), where);
                    rejects(scenario, p, value(p, p.min(), -1), where);
                    probed++;
                }
                if (p.max() != null && !p.name().equals("duration")) {
                    accepts(scenario, p, value(p, p.max(), 0), where);
                    rejects(scenario, p, value(p, p.max(), 1), where);
                    probed++;
                }
            }
        }
        assertThat(probed).isGreaterThan(20);
    }

    @Test
    void optionsAreTheValuesAccepted() {
        for (Scenario<?> scenario : kit.engine.scenarios()) {
            for (ScenarioCatalog.Param p : catalog.describe(scenario).params()) {
                if (p.options() == null) {
                    continue;
                }
                String where = scenario.name() + "." + p.name();
                for (String option : p.options()) {
                    JsonNode value = p.type() == ParamType.ENUM_LIST
                            ? ScenarioKit.MAPPER.createArrayNode().add(option)
                            : option.matches("\\d+")
                                    ? ScenarioKit.MAPPER.getNodeFactory().numberNode(Integer.parseInt(option))
                                    : ScenarioKit.MAPPER.getNodeFactory().stringNode(option);
                    accepts(scenario, p, value, where);
                }
            }
        }
    }

    @Test
    void typesMatchTheDocumentedCatalog() {
        Map<String, ScenarioCatalog.Param> bunching = params("bunching");
        assertThat(bunching.get("routeId").type()).isEqualTo(ParamType.ROUTE);
        assertThat(bunching.get("routeId").required()).isTrue();
        assertThat(bunching.get("directionId").type()).isEqualTo(ParamType.ENUM);
        assertThat(bunching.get("directionId").options()).containsExactly("0", "1");
        assertThat(bunching.get("pairs").type()).isEqualTo(ParamType.INT);
        assertThat(params("disruption").get("directionId").nullable()).isTrue();
        assertThat(params("disruption").get("directionId").required()).isFalse();
        assertThat(params("disruption").get("skipStops").type()).isEqualTo(ParamType.BOOLEAN);
        assertThat(params("bad-data").get("kinds").type()).isEqualTo(ParamType.ENUM_LIST);
        assertThat(params("bad-data").get("kinds").options()).contains("malformed_json", "delay_out_of_range");
        assertThat(params("bad-data").get("entityTypes").options()).containsExactly("VEHICLE_POSITION", "TRIP_UPDATE");
        assertThat(params("load-ramp").get("steps").type()).isEqualTo(ParamType.DOUBLE_LIST);

        ScenarioCatalog.Param salePoint = params("ticket-spike").get("salePointId");
        assertThat(salePoint.defaultValue().asString()).isEqualTo("KIOSK-001");
        assertThat(salePoint.suggestions()).hasSizeLessThanOrEqualTo(20).contains("KIOSK-001");
        assertThat(params("ticket-spike").get("duration").min()).isEqualTo(Duration.ofMinutes(15));
        assertThat(params("ticket-spike").get("extraPerMinute").suggestions()).isNull();
    }

    @Test
    void theCatalogIsJsonShapedLikeDoc25() {
        JsonNode json = ScenarioKit.MAPPER.valueToTree(catalog.describe(kit.engine.scenarios()));
        JsonNode spike = json.get("items").get(4);

        assertThat(spike.get("name").asString()).isEqualTo("ticket-spike");
        assertThat(spike.get("title").asString()).isEqualTo("Ticket sales spike");
        assertThat(spike.get("concurrency").asString()).isEqualTo("PER_TARGET");
        JsonNode duration = spike.get("params").get(2);
        assertThat(duration.get("default").asString()).isEqualTo("PT30M");
        assertThat(duration.get("min").asString()).isEqualTo("PT15M");
        assertThat(duration.get("max").asString()).isEqualTo("PT2H");
        assertThat(spike.get("params").get(1).get("min").asDouble()).isEqualTo(1.0);
        assertThat(spike.get("params").get(1).has("options")).isFalse();
    }

    private Map<String, ScenarioCatalog.Param> params(String scenario) {
        return catalog
                .describe(kit.engine.scenarios().stream()
                        .filter(s -> s.name().equals(scenario))
                        .findFirst()
                        .orElseThrow())
                .params()
                .stream()
                .collect(java.util.stream.Collectors.toMap(ScenarioCatalog.Param::name, p -> p));
    }

    private ObjectNode body(Scenario<?> scenario) {
        ObjectNode body = ScenarioKit.MAPPER.createObjectNode();
        for (RecordComponent c : scenario.paramsType().getRecordComponents()) {
            String value = REQUIRED.get(c.getName());
            if (value != null) {
                body.set(c.getName(), ScenarioKit.MAPPER.readTree(value));
            }
        }
        return body;
    }

    private <P extends Record> P bindAll(Scenario<P> scenario, JsonNode body) {
        return binder.bind(scenario, body);
    }

    private void accepts(Scenario<?> scenario, ScenarioCatalog.Param p, JsonNode value, String where) {
        ObjectNode body = body(scenario);
        ALONG_WITH.getOrDefault(p.name(), Map.of()).forEach((k, v) -> body.set(k, ScenarioKit.MAPPER.readTree(v)));
        body.set(p.name(), value);
        assertThatCode(() -> binder.bind(scenario, body))
                .as(where + " = " + value)
                .doesNotThrowAnyException();
    }

    private void rejects(Scenario<?> scenario, ScenarioCatalog.Param p, JsonNode value, String where) {
        ObjectNode body = body(scenario);
        ALONG_WITH.getOrDefault(p.name(), Map.of()).forEach((k, v) -> body.set(k, ScenarioKit.MAPPER.readTree(v)));
        body.set(p.name(), value);
        assertThatThrownBy(() -> binder.bind(scenario, body))
                .as(where + " = " + value)
                .isInstanceOfSatisfying(
                        ScenarioException.class,
                        e -> assertThat(e.errors())
                                .extracting(ScenarioException.FieldError::field)
                                .contains(p.name()));
    }

    /** The bound itself ({@code step = 0}) or just outside it ({@code step = ±1}). */
    private static JsonNode value(ScenarioCatalog.Param p, Object bound, int step) {
        var nodes = ScenarioKit.MAPPER.getNodeFactory();
        return switch (p.type()) {
            case INT -> nodes.numberNode(((Number) bound).intValue() + step);
            case DOUBLE ->
                nodes.numberNode(new BigDecimal(bound.toString())
                        .add(new BigDecimal("0.001").multiply(BigDecimal.valueOf(step))));
            case DOUBLE_LIST ->
                ScenarioKit.MAPPER
                        .createArrayNode()
                        .add(new BigDecimal(bound.toString())
                                .add(new BigDecimal("0.001").multiply(BigDecimal.valueOf(step))));
            case DURATION ->
                nodes.stringNode(((Duration) bound).plusSeconds(step).toString());
            default -> throw new IllegalStateException("No bounds for " + p.type());
        };
    }
}
