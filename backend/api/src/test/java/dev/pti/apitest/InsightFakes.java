package dev.pti.apitest;

import dev.pti.api.alert.application.port.AlertReader;
import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.application.port.DispatchSuggestionReader;
import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.application.port.OtpReader;
import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.common.events.UiEventPublisher;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The ports of the insight and alert features, the lookup of the ACTIVE feed and the publisher of UI events replaced by
 * in-memory ones, so that the web tests never reach for a database or a broker: a request that gets past the security
 * filters runs the real use case against memory. {@link ApiWebTestSupport} imports it; a test that wants data
 * autowires {@link InMemoryInsight}, {@link InMemoryAlerts} or {@link RecordingUiEvents} and resets it first.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InsightFakes {

    @Bean
    InMemoryInsight inMemoryInsight() {
        return new InMemoryInsight();
    }

    @Bean
    InMemoryAlerts inMemoryAlerts() {
        return new InMemoryAlerts();
    }

    @Bean
    RecordingUiEvents recordingUiEvents() {
        return new RecordingUiEvents();
    }

    /**
     * The use cases are wired with {@code readerTx} and {@code operatorTx}, which would open a real transaction on a
     * datasource: the beans are swapped for runners that just run the work.
     */
    @Bean
    static BeanPostProcessor directTransactionsOfInsight() {
        Set<String> names = Set.of("readerTx", "operatorTx");
        return new BeanPostProcessor() {
            @Override
            public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) {
                return names.contains(beanName) ? new DirectTransactions() : bean;
            }
        };
    }

    @Bean
    @Primary
    BunchingReader fakeBunching(InMemoryInsight insight) {
        return insight.bunchingReader;
    }

    @Bean
    @Primary
    DisruptionReader fakeDisruptions(InMemoryInsight insight) {
        return insight.disruptionReader;
    }

    @Bean
    @Primary
    OtpReader fakeOtp(InMemoryInsight insight) {
        return insight.otpReader;
    }

    @Bean
    @Primary
    TicketingAnomalyReader fakeTicketing(InMemoryInsight insight) {
        return insight.ticketingReader;
    }

    @Bean
    @Primary
    DispatchSuggestionReader fakeSuggestions(InMemoryInsight insight) {
        return insight.suggestionReader;
    }

    @Bean
    @Primary
    DispatchFeedbackStore fakeFeedback(InMemoryInsight insight) {
        return insight.feedbackStore;
    }

    @Bean
    @Primary
    AlertReader fakeAlertReader(InMemoryAlerts alerts) {
        return alerts.reader;
    }

    @Bean
    @Primary
    AlertStore fakeAlertStore(InMemoryAlerts alerts) {
        return alerts.store;
    }

    @Bean
    @Primary
    UiEventPublisher fakeUiEvents(RecordingUiEvents events) {
        return events;
    }
}
