package dev.pti.simulator.scenario;

import dev.pti.common.message.EntityType;
import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.emit.ResendQueue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;

/**
 * {@code duplicates} (DOC-25 §7.5, FR-03, EXP-02): sends a share of the messages again after a random delay in
 * {@code [minDelay, maxDelay]}. The original goes out unchanged; the resend waits in the {@link ResendQueue}, which
 * keeps sending after the run has ended.
 */
public final class DuplicatesScenario implements Scenario<DuplicatesScenario.Params> {

    public static final String NAME = "duplicates";

    public record Params(
            @ScenarioParam(label = "Share of messages") @NotNull @DecimalMin("0.001") @DecimalMax("1.0")
            Double ratio,

            @ScenarioParam(label = "Minimum delay") @NotNull @DurationMin(seconds = 0) @DurationMax(hours = 2)
            Duration minDelay,

            @ScenarioParam(label = "Maximum delay") @NotNull @DurationMin(seconds = 0) @DurationMax(hours = 2)
            Duration maxDelay,

            @ScenarioParam(label = "Entity types") @NotNull @NotEmpty
            List<@NotNull EntityType> entityTypes,

            @ScenarioParam(label = "Duration") @NotNull @DurationMin(minutes = 1)
            Duration duration) {

        public Params {
            entityTypes = entityTypes == null ? null : List.copyOf(entityTypes);
        }
    }

    private final ResendQueue resends;
    private final BusinessClock clock;

    public DuplicatesScenario(ResendQueue resends, BusinessClock clock) {
        this.resends = resends;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Duplicate messages";
    }

    @Override
    public String description() {
        return "Send a share of the GTFS-realtime messages again after a delay.";
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
                0.1,
                Duration.ZERO,
                Duration.ofSeconds(60),
                List.of(EntityType.VEHICLE_POSITION, EntityType.TRIP_UPDATE),
                Duration.ofMinutes(10));
    }

    @Override
    public List<ScenarioException.FieldError> check(Params params) {
        return params.maxDelay().compareTo(params.minDelay()) < 0
                ? List.of(new ScenarioException.FieldError("maxDelay", "must not be less than minDelay"))
                : List.of();
    }

    @Override
    public Duration duration(Params params) {
        return params.duration();
    }

    @Override
    public ScenarioHandle start(ScenarioContext context, Params params) {
        AtomicLong queued = new AtomicLong();
        AtomicLong dropped = new AtomicLong();
        long min = params.minDelay().toMillis();
        long spread = params.maxDelay().toMillis() - min;
        ScenarioHooks.Registration registration = context.hooks()
                .addInterceptor(context.runId(), ScenarioHooks.DUPLICATES_ORDER, (message, businessNow) -> {
                    EntityType entityType = EntityType.valueOf(message.ledger().entityType());
                    if (params.entityTypes().contains(entityType)
                            && Selection.picked(
                                    context.seed(), NAME, message.ledger().messageId(), params.ratio())) {
                        long delay = min
                                + Math.floorMod(
                                        Selection.value(
                                                context.seed(),
                                                NAME + ":delay",
                                                message.ledger().messageId()),
                                        spread + 1);
                        long due = clock.realNow().toEpochMilli() + delay;
                        (resends.add(message, due, context.runId()) ? queued : dropped).incrementAndGet();
                    }
                    return List.of(message);
                });
        return new ScenarioHandle() {
            @Override
            public void stop() {
                registration.remove();
            }

            @Override
            public Map<String, Object> progress() {
                return Map.of("queued", queued.get(), "dropped", dropped.get(), "queueDepth", resends.depth());
            }
        };
    }
}
