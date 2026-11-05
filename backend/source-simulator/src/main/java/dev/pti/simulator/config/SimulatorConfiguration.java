package dev.pti.simulator.config;

import dev.pti.common.gtfs.GtfsFormatException;
import dev.pti.common.json.JsonEnvelopeHasher;
import dev.pti.common.message.EnvelopeHasher;
import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.control.application.StatusService;
import dev.pti.simulator.emit.adapter.out.json.JacksonEnvelopeSerializer;
import dev.pti.simulator.emit.adapter.out.json.JacksonMessageRewriter;
import dev.pti.simulator.emit.adapter.out.kafka.KafkaFlush;
import dev.pti.simulator.emit.adapter.out.kafka.KafkaMessageSink;
import dev.pti.simulator.emit.adapter.out.metrics.MicrometerEmitterMetrics;
import dev.pti.simulator.emit.adapter.out.metrics.MicrometerResendMetrics;
import dev.pti.simulator.emit.adapter.out.sink.InterceptingSink;
import dev.pti.simulator.emit.application.Emitter;
import dev.pti.simulator.emit.application.ResendQueue;
import dev.pti.simulator.emit.application.port.EmitterMetrics;
import dev.pti.simulator.emit.application.port.MessageRewriter;
import dev.pti.simulator.emit.application.port.ResendMetrics;
import dev.pti.simulator.emit.domain.EnvelopeSerializer;
import dev.pti.simulator.emit.domain.MessageFactory;
import dev.pti.simulator.feed.adapter.out.gtfs.FeedLoader;
import dev.pti.simulator.feed.domain.Feed;
import dev.pti.simulator.feed.domain.ServiceDateMapper;
import dev.pti.simulator.feed.domain.ServiceDays;
import dev.pti.simulator.ledger.adapter.out.jdbc.LedgerWriter;
import dev.pti.simulator.ledger.application.port.Ledger;
import dev.pti.simulator.motion.domain.DelayModel;
import dev.pti.simulator.motion.domain.Fleet;
import dev.pti.simulator.observability.adapter.out.jdbc.ReplicationSlotProbe;
import dev.pti.simulator.rate.adapter.out.metrics.MicrometerRateMetrics;
import dev.pti.simulator.rate.application.RateControl;
import dev.pti.simulator.rate.application.port.RateMetrics;
import dev.pti.simulator.scenario.adapter.in.scheduling.ScenarioEngineLifecycle;
import dev.pti.simulator.scenario.application.ScenarioEngine;
import dev.pti.simulator.scenario.application.ScenarioHooks;
import dev.pti.simulator.tick.adapter.in.scheduling.TickLoop;
import dev.pti.simulator.tick.application.Throughput;
import dev.pti.simulator.ticketing.adapter.out.jdbc.JdbcTicketingRepository;
import dev.pti.simulator.ticketing.adapter.out.metrics.MicrometerTicketingMetrics;
import dev.pti.simulator.ticketing.application.TicketingSeeder;
import dev.pti.simulator.ticketing.application.port.TicketingMetrics;
import dev.pti.simulator.ticketing.domain.SaleGenerator;
import dev.pti.simulator.ticketing.domain.SalePointCatalog;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
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

    /** How often the engine ends runs whose time is up and advances load ramps. */
    private static final Duration SCENARIO_TICK = Duration.ofMillis(500);

    @Bean
    Feed feed(SimProperties properties) {
        try {
            return FeedLoader.load(properties.feed().location().getFile().toPath(), properties.feedSha256());
        } catch (IOException e) {
            throw new GtfsFormatException(
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
    RateMetrics rateMetrics(MeterRegistry registry) {
        return new MicrometerRateMetrics(registry);
    }

    @Bean
    RateControl rateControl(SimProperties properties, RateMetrics metrics) {
        return new RateControl(
                properties.rateMultiplier().gtfsRt(),
                properties.rateMultiplier().ticketing(),
                metrics);
    }

    @Bean
    EnvelopeHasher envelopeHasher() {
        return new JsonEnvelopeHasher();
    }

    @Bean
    EnvelopeSerializer envelopeSerializer() {
        return new JacksonEnvelopeSerializer();
    }

    @Bean
    MessageFactory messageFactory(
            BusinessClock clock, SimProperties properties, EnvelopeHasher hasher, EnvelopeSerializer serializer) {
        return new MessageFactory(
                clock,
                properties.seed(),
                properties.gpsNoise(),
                properties.vehiclePosition().v2Ratio(),
                properties.tripUpdate().lookaheadStops(),
                hasher,
                serializer);
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

    @Bean
    ResendMetrics resendMetrics(MeterRegistry registry) {
        return new MicrometerResendMetrics(registry);
    }

    @Bean
    MessageRewriter messageRewriter() {
        return new JacksonMessageRewriter();
    }

    /** Resends of the {@code duplicates} scenario, sent on {@code sim-resend} (DOC-25 §7.5, §10). */
    @Bean
    ResendQueue resendQueue(
            KafkaMessageSink sink,
            BusinessClock clock,
            SimProperties properties,
            ResendMetrics metrics,
            MessageRewriter rewriter) {
        return new ResendQueue(sink, clock, properties.duplicates().queueCapacity(), metrics, rewriter);
    }

    @Bean
    TickLoop resendLoop(ResendQueue resends, SimProperties properties) {
        return new TickLoop("sim-resend", resends::tick, properties.tick());
    }

    @Bean
    EmitterMetrics emitterMetrics(MeterRegistry registry) {
        return new MicrometerEmitterMetrics(registry);
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
            EmitterMetrics metrics) {
        Fleet fleet = new Fleet(
                serviceDays,
                model,
                properties.vehicle().maxLayoverEmit(),
                properties.delay().lateLimit().plus(LATE_MARGIN));
        return new Emitter(
                clock,
                fleet,
                messages,
                new InterceptingSink(sink, hooks, clock),
                rate,
                properties.vehiclePosition().interval(),
                properties.tripUpdate().interval(),
                hooks,
                metrics);
    }

    @Bean
    TicketingMetrics ticketingMetrics(MeterRegistry registry) {
        return new MicrometerTicketingMetrics(registry);
    }

    @Bean
    TicketingSeeder ticketingSeeder(
            BusinessClock clock,
            Feed feed,
            SalePointCatalog catalog,
            @Qualifier("ticketingJdbcTemplate") JdbcTemplate jdbc,
            RateControl rate,
            SimProperties properties,
            TicketingMetrics metrics,
            Throughput throughput,
            ScenarioHooks hooks) {
        SaleGenerator generator = new SaleGenerator(catalog, properties.ticketing(), feed.zone(), properties.seed());
        return new TicketingSeeder(
                clock,
                generator,
                catalog,
                new JdbcTicketingRepository(jdbc),
                rate,
                properties.ticketing(),
                properties.seed(),
                metrics,
                throughput,
                hooks::ticketing);
    }

    @Bean
    TickLoop ticketingLoop(TicketingSeeder seeder, SimProperties properties) {
        return new TickLoop("sim-ticketing", seeder::tick, properties.tick());
    }

    @Bean
    ScenarioEngineLifecycle scenarioEngineLifecycle(ScenarioEngine engine) {
        return new ScenarioEngineLifecycle(engine);
    }

    @Bean
    TickLoop scenarioLoop(ScenarioEngine engine) {
        return new TickLoop("sim-scenarios", engine::tick, SCENARIO_TICK);
    }

    @Bean
    StatusService statusService(
            BusinessClock clock,
            Feed feed,
            ServiceDays serviceDays,
            RateControl rate,
            Emitter emitter,
            Throughput throughput,
            Ledger ledger,
            ScenarioEngine scenarios) {
        return new StatusService(clock, feed, serviceDays, rate, emitter, throughput, ledger, scenarios);
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
