package dev.pti.etl.config;

import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.error.FatalException;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.MessageProcessors;
import dev.pti.etl.fault.FaultInjector;
import dev.pti.etl.flags.RuntimeFlags;
import dev.pti.etl.health.ConnectorStatus;
import dev.pti.etl.health.ReferenceDataHealthIndicator;
import dev.pti.etl.health.SourceHealthIndicator;
import dev.pti.etl.health.WarehouseHealthIndicator;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.stream.BaselineListeners;
import dev.pti.etl.stream.DataBatchFailedException;
import dev.pti.etl.stream.EtlListeners;
import dev.pti.etl.stream.ListenerLifecycleManager;
import dev.pti.etl.stream.ListenerPauseCoordinator;
import dev.pti.etl.stream.PollTracing;
import dev.pti.etl.stream.SourceActivity;
import dev.pti.etl.stream.StreamBatchLog;
import dev.pti.etl.stream.StreamChunkHandler;
import dev.pti.etl.stream.StreamChunkTemplate;
import dev.pti.etl.stream.StreamListener;
import dev.pti.etl.stream.TopicNames;
import dev.pti.etl.write.BaselineFactWriter;
import dev.pti.etl.write.ChunkWriter;
import dev.pti.etl.write.DeadLetterWriter;
import dev.pti.etl.write.FactChunkWriter;
import dev.pti.etl.write.WriteStats;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonDelegatingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestClient;

/** {@code etl-stream} (DOC-20): Kafka consumers, error handling, circuit breaker, pause flags and health. */
@Configuration(proxyBeanMethods = false)
@Profile("stream")
public class EtlStreamConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EtlStreamConfiguration.class);

    @Bean
    TopicNames topicNames(PlatformProperties platform) {
        return new TopicNames(platform.kafka().topicPrefix());
    }

    /** DOC-20 §2. Values stay bytes so that bad UTF-8 becomes a dead letter instead of a consumer error. */
    @Bean
    ConsumerFactory<String, byte[]> etlConsumerFactory(KafkaProperties kafka, EtlProperties etl, MeterRegistry meters) {
        Map<String, Object> props = kafka.buildConsumerProperties();
        boolean autoCommit = etl.baseline().offsetCommit() == EtlProperties.OffsetCommit.AUTO;
        props.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "classic");
        props.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, CooperativeStickyAssignor.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit);
        if (autoCommit) {
            props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 1000);
        }
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, etl.consumer().maxPollRecords());
        props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, etl.consumer().fetchMinBytes());
        props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, (int)
                etl.consumer().fetchMaxWait().toMillis());
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3_000);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_uncommitted");
        DefaultKafkaConsumerFactory<String, byte[]> factory =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new ByteArrayDeserializer());
        factory.addListener(new MicrometerConsumerListener<>(meters));
        return factory;
    }

    @Bean
    ThreadPoolTaskScheduler kafkaPauseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("kafka-pause-");
        return scheduler;
    }

    /**
     * DOC-20 §5.1: infrastructure errors retry the whole poll forever with back-off, pausing the container meanwhile
     * so that it keeps its partitions; a {@link FatalException} stops the container. In the experiment profile a
     * baseline {@link DataBatchFailedException} drops the poll (DR-27).
     */
    @Bean
    CommonErrorHandler etlErrorHandler(
            KafkaListenerEndpointRegistry registry, ThreadPoolTaskScheduler kafkaPauseScheduler, EtlProperties etl) {
        ExponentialBackOff backOff = new ExponentialBackOff(
                etl.retry().initialInterval().toMillis(), etl.retry().multiplier());
        backOff.setMaxInterval(etl.retry().maxInterval().toMillis());
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        DefaultErrorHandler retrying = new DefaultErrorHandler(
                (record, e) -> {
                    throw new IllegalStateException("The recoverer must never be reached", e);
                },
                backOff,
                new ContainerPausingBackOffHandler(new ListenerContainerPauseService(registry, kafkaPauseScheduler)));
        retrying.setClassifications(Map.of(), true);
        // Each retry of an infrastructure error re-seeks the poll; that is expected, so WARN, not ERROR (DOC-19 §10).
        retrying.setLogLevel(KafkaException.Level.WARN);

        Map<Class<? extends Throwable>, CommonErrorHandler> delegates = new LinkedHashMap<>();
        delegates.put(FatalException.class, new CommonContainerStoppingErrorHandler());
        if (etl.baseline().errorMode() == EtlProperties.ErrorMode.FAIL_BATCH) {
            delegates.put(
                    DataBatchFailedException.class,
                    new DefaultErrorHandler(
                            (record, e) -> log.warn(
                                    "Baseline drops record {}-{}@{}",
                                    record.topic(),
                                    record.partition(),
                                    record.offset()),
                            new FixedBackOff(0, 0)));
        }
        CommonDelegatingErrorHandler handler = new CommonDelegatingErrorHandler(retrying);
        handler.setErrorHandlers(delegates);
        handler.setCauseChainTraversing(true);
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> etlBatchListenerFactory(
            ConsumerFactory<String, byte[]> etlConsumerFactory, CommonErrorHandler etlErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(etlConsumerFactory);
        factory.setBatchListener(true);
        factory.setCommonErrorHandler(etlErrorHandler);
        ContainerProperties container = factory.getContainerProperties();
        container.setAckMode(ContainerProperties.AckMode.BATCH);
        container.setSyncCommits(true);
        container.setSyncCommitTimeout(Duration.ofSeconds(10));
        container.setPollTimeout(1_000);
        container.setShutdownTimeout(30_000);
        container.setMissingTopicsFatal(true);
        container.setObservationEnabled(true);
        return factory;
    }

    @Bean
    ListenerPauseCoordinator listenerPauseCoordinator(
            KafkaListenerEndpointRegistry registry, MeterRegistry meters, TopicNames topics, Environment env) {
        ListenerPauseCoordinator coordinator = new ListenerPauseCoordinator(registry, isBaseline(env));
        coordinator.bindTo(meters, topics);
        return coordinator;
    }

    /** DOC-20 §5.2: an open circuit pauses every container; half-open lets a few polls try again. */
    @Bean
    CircuitBreaker warehouseCircuitBreaker(CircuitBreakerRegistry registry, ListenerPauseCoordinator coordinator) {
        CircuitBreaker breaker = registry.circuitBreaker("warehouse");
        breaker.getEventPublisher().onStateTransition(e -> coordinator.onCircuit(e.getStateTransition()));
        return breaker;
    }

    @Bean
    StreamBatchLog streamBatchLog(NamedParameterJdbcTemplate jdbc) {
        return new StreamBatchLog(jdbc);
    }

    @Bean
    StreamChunkTemplate streamChunkTemplate(
            TransactionTemplate tx,
            DeadLetterWriter deadLetters,
            StreamBatchLog batchLog,
            ErrorClassifier classifier,
            FaultInjector faults,
            ApplicationEventPublisher events,
            BusinessClock clock,
            ReferenceDataHolder reference,
            WriteStats stats,
            MeterRegistry meters,
            EtlProperties etl,
            Environment env,
            ObjectProvider<Tracer> tracer,
            @Value("${pti.etl.trace.max-links:20}") int maxLinks) {
        return new StreamChunkTemplate(
                tx,
                deadLetters,
                batchLog,
                classifier,
                faults,
                // DOC-20 §9: the baseline publishes nothing after a commit.
                isBaseline(env) ? event -> {} : events,
                clock,
                reference,
                stats,
                meters,
                etl.baseline().errorMode() == EtlProperties.ErrorMode.FAIL_BATCH,
                new PollTracing(tracer.getIfAvailable(() -> Tracer.NOOP), maxLinks));
    }

    @Bean
    StreamChunkHandler streamChunkHandler(
            StreamChunkTemplate template,
            MessageProcessors processors,
            FactChunkWriter writer,
            NamedParameterJdbcTemplate named,
            WriteStats stats,
            EtlProperties etl,
            CircuitBreaker warehouseCircuitBreaker,
            ErrorClassifier classifier,
            @Value("${HOSTNAME:local}") String instanceId,
            MeterRegistry meters) {
        ChunkWriter chunkWriter = etl.baseline().writeMode() == EtlProperties.WriteMode.INSERT
                ? new BaselineFactWriter(named, stats)
                : writer;
        return new StreamChunkHandler(
                template, processors, chunkWriter, warehouseCircuitBreaker, classifier, instanceId, meters);
    }

    @Bean
    @Profile("!experiment")
    EtlListeners etlListeners(StreamChunkHandler handler) {
        return new EtlListeners(handler);
    }

    /** DR-27: in the experiment profile only the baseline group consumes (DOC-20 §9). */
    @Bean
    @Profile("experiment")
    BaselineListeners baselineListeners(StreamChunkHandler handler) {
        return new BaselineListeners(handler);
    }

    @Bean
    ListenerLifecycleManager listenerLifecycleManager(
            KafkaListenerEndpointRegistry registry,
            ReferenceDataHolder reference,
            ListenerPauseCoordinator pauses,
            Environment env) {
        return new ListenerLifecycleManager(registry, reference, pauses, isBaseline(env));
    }

    private static boolean isBaseline(Environment env) {
        return env.acceptsProfiles(Profiles.of("experiment"));
    }

    @Bean
    SourceActivity sourceActivity() {
        return new SourceActivity(Clock.systemUTC());
    }

    @Bean
    RuntimeFlags runtimeFlags(JdbcTemplate jdbc, ApplicationEventPublisher events) {
        return new RuntimeFlags(jdbc, events);
    }

    @Bean
    FlagSchedule flagSchedule(RuntimeFlags flags, ListenerPauseCoordinator coordinator) {
        return new FlagSchedule(flags, coordinator);
    }

    // ---------------------------------------------------------------- health (DOC-20 §6.1, DR-38)

    @Bean("referenceData")
    ReferenceDataHealthIndicator referenceDataHealthIndicator(ReferenceDataHolder holder) {
        return new ReferenceDataHealthIndicator(holder);
    }

    @Bean("warehouse-db")
    WarehouseHealthIndicator warehouseHealthIndicator(JdbcTemplate jdbc, CircuitBreaker warehouseCircuitBreaker) {
        return new WarehouseHealthIndicator(jdbc, warehouseCircuitBreaker, Clock.systemUTC());
    }

    @Bean
    ConnectorStatus connectorStatus(EtlProperties etl, MeterRegistry meters) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        requests.setReadTimeout(Duration.ofSeconds(2));
        ConnectorStatus status = new ConnectorStatus(
                RestClient.builder().requestFactory(requests), etl.health().connectUrl(), Clock.systemUTC());
        status.bindTo(meters);
        return status;
    }

    @Bean("source-gtfs-rt")
    SourceHealthIndicator gtfsRtSourceHealth(
            WarehouseHealthIndicator warehouse,
            ListenerPauseCoordinator pauses,
            SourceActivity activity,
            EtlProperties etl,
            MeterRegistry meters) {
        SourceHealthIndicator health = new SourceHealthIndicator(
                List.of(StreamListener.GTFS_RT_VEHICLE_POSITION, StreamListener.GTFS_RT_TRIP_UPDATE),
                warehouse,
                pauses,
                activity,
                etl.health().freshness(),
                Clock.systemUTC(),
                null,
                () -> false);
        health.bindTo(meters, "gtfs-rt");
        return health;
    }

    @Bean("source-ticketing")
    SourceHealthIndicator ticketingSourceHealth(
            WarehouseHealthIndicator warehouse,
            ListenerPauseCoordinator pauses,
            SourceActivity activity,
            ConnectorStatus connector,
            KafkaListenerEndpointRegistry registry,
            EtlProperties etl,
            MeterRegistry meters) {
        List<StreamListener> listeners = List.of(StreamListener.TICKETING_SALES, StreamListener.TICKETING_SALE_POINTS);
        SourceHealthIndicator health = new SourceHealthIndicator(
                listeners,
                warehouse,
                pauses,
                activity,
                etl.health().freshness(),
                Clock.systemUTC(),
                connector,
                () -> listeners.stream().allMatch(l -> caughtUp(registry.getListenerContainer(l.id()))));
        health.bindTo(meters, "ticketing");
        return health;
    }

    /** The consumer's {@code records-lag-max} is zero, or unknown because nothing was fetched lately. */
    static boolean caughtUp(MessageListenerContainer container) {
        if (container == null) {
            return false;
        }
        for (Map<MetricName, ? extends Metric> metrics : container.metrics().values()) {
            for (Map.Entry<MetricName, ? extends Metric> e : metrics.entrySet()) {
                if (e.getKey().name().equals("records-lag-max")
                        && e.getKey().tags().get("topic") == null) {
                    Object value = e.getValue().metricValue();
                    if (value instanceof Double lag && !lag.isNaN() && lag > 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Runtime flags every 5 seconds (DOC-19 §2.1), then the pause state is re-applied. */
    static final class FlagSchedule {

        private final RuntimeFlags flags;
        private final ListenerPauseCoordinator coordinator;

        FlagSchedule(RuntimeFlags flags, ListenerPauseCoordinator coordinator) {
            this.flags = flags;
            this.coordinator = coordinator;
        }

        @Scheduled(initialDelay = 0, fixedDelayString = "5s", scheduler = "ptiTaskScheduler")
        void refresh() {
            flags.refresh();
            coordinator.reconcile();
        }
    }
}
