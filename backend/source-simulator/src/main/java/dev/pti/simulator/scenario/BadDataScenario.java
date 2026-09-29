package dev.pti.simulator.scenario;

import dev.pti.common.message.EntityType;
import dev.pti.simulator.emit.OutboundMessage;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.validator.constraints.time.DurationMin;
import org.jspecify.annotations.Nullable;

/**
 * {@code bad-data} (DOC-25 §7.4, FR-02, EXP-03): replaces a share of the messages with corrupted ones. The kind is
 * chosen per message among the requested kinds that apply to its entity type; a message none of them applies to is
 * sent as is.
 */
public final class BadDataScenario implements Scenario<BadDataScenario.Params> {

    public static final String NAME = "bad-data";

    public record Params(
            @ScenarioParam(label = "Share of messages") @NotNull @DecimalMin("0.001") @DecimalMax("0.5")
            Double ratio,

            @ScenarioParam(label = "Kinds of corruption") @NotNull @NotEmpty
            List<@NotNull InvalidKind> kinds,

            @ScenarioParam(label = "Entity types") @NotNull @NotEmpty
            List<@NotNull EntityType> entityTypes,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 1)
            Duration duration) {

        public Params {
            kinds = kinds == null ? null : List.copyOf(kinds);
            entityTypes = entityTypes == null ? null : List.copyOf(entityTypes);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Bad data";
    }

    @Override
    public String description() {
        return "Corrupt a share of the GTFS-realtime messages.";
    }

    @Override
    public Concurrency concurrency() {
        return Concurrency.SINGLE;
    }

    @Override
    public Class<Params> paramsType() {
        return Params.class;
    }

    @Override
    public Params defaults() {
        return new Params(
                0.05,
                List.of(InvalidKind.values()),
                List.of(EntityType.VEHICLE_POSITION, EntityType.TRIP_UPDATE),
                Duration.ofMinutes(10));
    }

    @Override
    public Duration duration(Params params) {
        return params.duration();
    }

    @Override
    public ScenarioHandle start(ScenarioContext context, Params params) {
        Map<InvalidKind, AtomicLong> counts = new EnumMap<>(InvalidKind.class);
        params.kinds().forEach(k -> counts.put(k, new AtomicLong()));
        ScenarioHooks.Registration registration = context.hooks()
                .addInterceptor(context.runId(), ScenarioHooks.BAD_DATA_ORDER, (message, businessNow) -> {
                    OutboundMessage corrupted = corrupt(context, params, message, businessNow);
                    if (corrupted == null) {
                        return List.of(message);
                    }
                    counts.get(InvalidKind.of(corrupted.ledger().invalidKind())).incrementAndGet();
                    return List.of(corrupted);
                });
        return new ScenarioHandle() {
            @Override
            public void stop() {
                registration.remove();
            }

            @Override
            public Map<String, Object> progress() {
                Map<String, Object> corrupted = new LinkedHashMap<>();
                counts.forEach((kind, n) -> corrupted.put(kind.value(), n.get()));
                return Map.of("corrupted", corrupted);
            }
        };
    }

    private static @Nullable OutboundMessage corrupt(
            ScenarioContext context, Params params, OutboundMessage message, long businessNow) {
        EntityType entityType = EntityType.valueOf(message.ledger().entityType());
        if (message.ledger().invalidKind() != null
                || !params.entityTypes().contains(entityType)
                || !Selection.picked(context.seed(), NAME, message.ledger().messageId(), params.ratio())) {
            return null;
        }
        List<InvalidKind> kinds =
                params.kinds().stream().filter(k -> k.appliesTo(entityType)).toList();
        if (kinds.isEmpty()) {
            return null;
        }
        long choice =
                Selection.value(context.seed(), NAME + ":kind", message.ledger().messageId());
        InvalidKind kind = kinds.get((int) Math.floorMod(choice, (long) kinds.size()));
        return MessageCorruptor.corrupt(message, kind, choice >>> 8, businessNow, context.runId());
    }
}
