package dev.pti.etl.config;

import dev.pti.common.classifier.PlatformErrorClassifier;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.json.MessageSchemas;
import dev.pti.common.pii.PiiScrubber;
import dev.pti.common.time.BusinessClock;
import dev.pti.etl.core.adapter.out.json.CdcReader;
import dev.pti.etl.core.adapter.out.json.EnvelopeReader;
import dev.pti.etl.core.application.MessageProcessor;
import dev.pti.etl.core.application.MessageProcessors;
import dev.pti.etl.core.application.SalePointCdcProcessor;
import dev.pti.etl.core.application.TicketSaleCdcProcessor;
import dev.pti.etl.core.application.TripUpdateProcessor;
import dev.pti.etl.core.application.VehiclePositionProcessor;
import dev.pti.etl.fault.adapter.out.chaos.ConfigurableFaultInjector;
import dev.pti.etl.fault.application.port.FaultInjector;
import dev.pti.etl.reference.ReferenceDataHolder;
import dev.pti.etl.reference.ReferenceDataLoader;
import dev.pti.etl.reference.ReferenceDataRefresher;
import dev.pti.etl.rules.domain.RealtimeRules;
import dev.pti.etl.rules.domain.RuleEngine;
import dev.pti.etl.rules.domain.TicketRules;
import dev.pti.etl.write.adapter.out.jdbc.DedupRegistry;
import dev.pti.etl.write.adapter.out.jdbc.FactChunkWriter;
import dev.pti.etl.write.adapter.out.jdbc.JdbcDeadLetterWriter;
import dev.pti.etl.write.application.WriteStats;
import dev.pti.etl.write.application.port.DeadLetterWriter;
import dev.pti.etl.write.domain.KnownKeyCache;
import dev.pti.etl.write.domain.RefundRule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Beans shared by the {@code stream} and {@code batch} profiles: processors, rules, writer, reference data. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
    EtlProperties.class,
    BatchJobProperties.class,
    GtfsProperties.class,
    ReplayProperties.class,
    DqProperties.class,
    RetentionProperties.class,
    PlatformProperties.class
})
public class EtlCoreConfiguration {

    /** The business clock (DR-67): the only place that reads the system clock. */
    @Bean
    BusinessClock businessClock(@Value("${pti.clock.offset:0s}") Duration offset) {
        return new BusinessClock(Clock.systemUTC(), offset);
    }

    @Bean
    BaselineModeGuard baselineModeGuard(EtlProperties etl, Environment env) {
        return new BaselineModeGuard(etl, env);
    }

    /** Every {@code @Scheduled} task runs here, apart from job execution (DOC-19 §2.1). */
    @Bean
    ThreadPoolTaskScheduler ptiTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("pti-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    ErrorClassifier errorClassifier() {
        return new PlatformErrorClassifier();
    }

    @Bean
    PiiScrubber piiScrubber(PlatformProperties platform) {
        return new PiiScrubber(platform.pii().blocklist());
    }

    @Bean
    MessageSchemas messageSchemas() {
        return MessageSchemas.load();
    }

    @Bean
    EnvelopeReader envelopeReader(MessageSchemas schemas, Validator validator) {
        return new EnvelopeReader(schemas, validator);
    }

    @Bean
    CdcReader cdcReader(Validator validator) {
        return new CdcReader(validator);
    }

    @Bean
    VehiclePositionProcessor vehiclePositionProcessor(EnvelopeReader reader, DqProperties dq) {
        return new VehiclePositionProcessor(reader, new RuleEngine<>(RealtimeRules.all(dq), dq));
    }

    @Bean
    TripUpdateProcessor tripUpdateProcessor(EnvelopeReader reader, DqProperties dq) {
        return new TripUpdateProcessor(reader, new RuleEngine<>(RealtimeRules.all(dq), dq));
    }

    /** {@code sale_date} is the agency date of {@code created_at} (DOC-20 §4.4). */
    @Bean
    TicketSaleCdcProcessor ticketSaleCdcProcessor(CdcReader reader, DqProperties dq, GtfsProperties gtfs) {
        return new TicketSaleCdcProcessor(
                reader,
                new RuleEngine<>(TicketRules.all(dq), dq),
                gtfs.staticFeed().zone());
    }

    @Bean
    SalePointCdcProcessor salePointCdcProcessor(CdcReader reader) {
        return new SalePointCdcProcessor(reader);
    }

    @Bean
    MessageProcessors messageProcessors(List<MessageProcessor> processors) {
        return new MessageProcessors(processors);
    }

    @Bean
    ReferenceDataHolder referenceDataHolder() {
        return new ReferenceDataHolder();
    }

    @Bean
    ReferenceDataLoader referenceDataLoader(JdbcTemplate jdbc, EtlProperties etl) {
        return new ReferenceDataLoader(jdbc, etl.reference().extraDays());
    }

    @Bean
    ReferenceDataRefresher referenceDataRefresher(
            ReferenceDataLoader loader,
            ReferenceDataHolder holder,
            BusinessClock clock,
            ApplicationEventPublisher events,
            MeterRegistry meters) {
        return new ReferenceDataRefresher(loader, holder, clock, events, meters);
    }

    /** Loads the ACTIVE feed at startup and then every {@code pti.etl.reference.refresh-interval} (DOC-20 §6). */
    @Bean
    ReferenceRefreshSchedule referenceRefreshSchedule(ReferenceDataRefresher refresher) {
        return new ReferenceRefreshSchedule(refresher);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    DeadLetterWriter deadLetterWriter(
            NamedParameterJdbcTemplate jdbc, PiiScrubber scrubber, PlatformProperties platform, MeterRegistry meters) {
        return new JdbcDeadLetterWriter(
                jdbc, scrubber, (int) platform.dlq().maxPayloadBytes().toBytes(), meters);
    }

    @Bean
    WriteStats writeStats(MeterRegistry meters) {
        return new WriteStats(meters);
    }

    @Bean
    FactChunkWriter factChunkWriter(
            NamedParameterJdbcTemplate named,
            JdbcTemplate jdbc,
            DeadLetterWriter deadLetters,
            WriteStats stats,
            EtlProperties etl,
            DqProperties dq) {
        boolean dedup = etl.dedup().enabled() && etl.baseline().dedup() == EtlProperties.DedupMode.ON;
        return new FactChunkWriter(
                named,
                new RefundRule(jdbc, dq),
                new DedupRegistry(jdbc),
                dedup,
                deadLetters,
                new KnownKeyCache(etl.knownKeyCache().maxSize()),
                stats,
                dq);
    }

    @Bean
    @Profile("!test & !experiment")
    FaultInjector faultInjector() {
        return FaultInjector.NOOP;
    }

    /** DOC-19 §8: {@code pti.test.fault.*} only takes effect in the test and experiment profiles. */
    @Bean
    @Profile("test | experiment")
    ConfigurableFaultInjector configurableFaultInjector(Environment env) {
        return ConfigurableFaultInjector.fromEnvironment(env);
    }

    /** The scheduled refresh; a separate bean so that the refresher itself stays free of Spring annotations. */
    static final class ReferenceRefreshSchedule {

        private final ReferenceDataRefresher refresher;

        ReferenceRefreshSchedule(ReferenceDataRefresher refresher) {
            this.refresher = refresher;
        }

        @Scheduled(
                initialDelayString = "0",
                fixedDelayString = "${pti.etl.reference.refresh-interval:30s}",
                scheduler = "ptiTaskScheduler")
        void refresh() {
            refresher.refresh();
        }
    }
}
