package dev.pti.simulator;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.emit.Emitter;
import dev.pti.simulator.emit.InterceptingSink;
import dev.pti.simulator.emit.KafkaFlush;
import dev.pti.simulator.emit.KafkaMessageSink;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.emit.ResendQueue;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.FeedLoader;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.ledger.Ledger;
import dev.pti.simulator.ledger.LedgerWriter;
import dev.pti.simulator.motion.DelayModel;
import dev.pti.simulator.motion.Fleet;
import dev.pti.simulator.observability.ReplicationSlotProbe;
import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.scenario.ScenarioHooks;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/** Wires the GTFS-realtime side of the simulator (DOC-25 §4–§6). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimProperties.class)
public class SimulatorConfiguration {

    /** A block may end this long after its scheduled end, on top of the delay model's late limit. */
    private static final Duration LATE_MARGIN = Duration.ofMinutes(10);

    @Bean
    Feed feed(SimProperties properties) {
        try {
            return FeedLoader.load(properties.feed().location().getFile().toPath(), properties.feedSha256());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot open GTFS feed " + properties.feed().location(), e);
        }
    }

    @Bean
    ServiceDays serviceDays(Feed feed, SimProperties properties) {
        return new ServiceDays(
                feed,
                new ServiceDateMapper(feed.calendar(), properties.serviceDateMapping()),
                properties.vehicle().minLayover());
    }

    /** Where running scenarios attach (DOC-25 §7.1). */
    @Bean
    ScenarioHooks scenarioHooks() {
        return new ScenarioHooks();
    }

    @Bean
    DelayModel delayModel(Feed feed, SimProperties properties, ScenarioHooks hooks) {
        return new DelayModel(properties.seed(), properties.delayParameters(), feed.zone(), hooks);
    }

    @Bean
    RateControl rateControl(SimProperties properties, MeterRegistry registry) {
        RateControl rate = new RateControl(
                properties.rateMultiplier().gtfsRt(),
                properties.rateMultiplier().ticketing());
        rate.bindTo(registry);
        return rate;
    }

    @Bean
    MessageFactory messageFactory(BusinessClock clock, SimProperties properties) {
        return new MessageFactory(
                clock,
                properties.seed(),
                properties.gpsNoise(),
                properties.vehiclePosition().v2Ratio(),
                properties.tripUpdate().lookaheadStops());
    }

    @Bean
    LedgerWriter ledger(
            @Qualifier("simJdbcTemplate") JdbcTemplate jdbc,
            BusinessClock clock,
            SimProperties properties,
            MeterRegistry registry) {
        SimProperties.LedgerSettings settings = properties.ledger();
        return new LedgerWriter(
                jdbc,
                clock,
                settings.queueCapacity(),
                settings.batchSize(),
                settings.flushInterval(),
                settings.retention(),
                registry);
    }

    @Bean
    Throughput throughput(BusinessClock clock) {
        return new Throughput(() -> clock.realNow().toEpochMilli());
    }

    @Bean
    KafkaMessageSink kafkaMessageSink(
            KafkaTemplate<String, String> template,
            Ledger ledger,
            BusinessClock clock,
            MeterRegistry registry,
            Throughput throughput) {
        return new KafkaMessageSink(template, ledger, clock, registry, throughput);
    }

    @Bean
    KafkaFlush kafkaFlush(KafkaMessageSink sink) {
        return new KafkaFlush(sink);
    }

    /** Resends of the {@code duplicates} scenario, sent on {@code sim-resend} (DOC-25 §7.5, §10). */
    @Bean
    ResendQueue resendQueue(
            KafkaMessageSink sink, BusinessClock clock, SimProperties properties, MeterRegistry registry) {
        return new ResendQueue(sink, clock, properties.duplicates().queueCapacity(), registry);
    }

    @Bean
    TickLoop resendLoop(ResendQueue resends, SimProperties properties) {
        return new TickLoop("sim-resend", resends::tick, properties.tick());
    }

    @Bean
    Emitter emitter(
            BusinessClock clock,
            ServiceDays serviceDays,
            DelayModel model,
            MessageFactory messages,
            KafkaMessageSink sink,
            RateControl rate,
            ScenarioHooks hooks,
            SimProperties properties,
            MeterRegistry registry) {
        Fleet fleet = new Fleet(
                serviceDays,
                model,
                properties.vehicle().maxLayoverEmit(),
                properties.delay().lateLimit().plus(LATE_MARGIN));
        Emitter emitter = new Emitter(
                clock,
                fleet,
                messages,
                new InterceptingSink(sink, hooks, clock),
                rate,
                properties.vehiclePosition().interval(),
                properties.tripUpdate().interval(),
                hooks);
        emitter.bindTo(registry);
        return emitter;
    }

    /** The WAL kept by Debezium's replication slot (DR-71), probed on its own thread. */
    @Bean
    TickLoop slotProbeLoop(
            @Qualifier("simJdbcTemplate") JdbcTemplate jdbc,
            @Value("${pti.observability.slot-probe.interval:30s}") Duration interval,
            MeterRegistry registry) {
        ReplicationSlotProbe probe = new ReplicationSlotProbe(jdbc, registry);
        return new TickLoop("sim-slot-probe", probe::probe, interval);
    }

    @Bean
    TickLoop emitterLoop(Emitter emitter, SimProperties properties) {
        return new TickLoop("sim-emitter", emitter::tick, properties.tick());
    }
}
