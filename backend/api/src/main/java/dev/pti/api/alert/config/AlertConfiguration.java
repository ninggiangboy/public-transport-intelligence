package dev.pti.api.alert.config;

import dev.pti.api.alert.application.AcknowledgeAlert;
import dev.pti.api.alert.application.ListAlerts;
import dev.pti.api.alert.application.ReceiveAlertmanagerWebhook;
import dev.pti.api.alert.application.port.AlertReader;
import dev.pti.api.alert.application.port.AlertStore;
import dev.pti.api.alert.application.port.WebhookMetrics;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.common.events.UiEventPublisher;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases of the {@code alert} feature (DOC-32 §5, E-80): the list reads through {@code readerTx}, the
 * acknowledgement and the webhook write through {@code operatorTx} and tell the screens after the commit (DOC-31
 * §10.1, DOC-49 §5.2). The JDBC and metrics adapters are components: they name the query timer of the platform or the
 * meter registry in their constructors, which a {@code config} class may not (A-14).
 */
@Configuration(proxyBeanMethods = false)
class AlertConfiguration {

    @Bean
    ListAlerts listAlerts(AlertReader alerts, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListAlerts(alerts, tx);
    }

    @Bean
    AcknowledgeAlert acknowledgeAlert(
            AlertStore store,
            UiEventPublisher events,
            WriteMetrics metrics,
            BusinessClock clock,
            @Qualifier("operatorTx") TransactionRunner tx) {
        return new AcknowledgeAlert(store, events, metrics, clock, tx);
    }

    @Bean
    ReceiveAlertmanagerWebhook receiveAlertmanagerWebhook(
            AlertStore store,
            UiEventPublisher events,
            WebhookMetrics metrics,
            BusinessClock clock,
            @Qualifier("operatorTx") TransactionRunner tx) {
        return new ReceiveAlertmanagerWebhook(store, events, metrics, clock, tx);
    }
}
