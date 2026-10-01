package dev.pti.api.insight.config;

import dev.pti.api.insight.application.GetBunchingEpisode;
import dev.pti.api.insight.application.GetDisruptionEpisode;
import dev.pti.api.insight.application.GetOtpScorecard;
import dev.pti.api.insight.application.GetTicketingAnomaly;
import dev.pti.api.insight.application.ListBunchingEpisodes;
import dev.pti.api.insight.application.ListDispatchSuggestions;
import dev.pti.api.insight.application.ListDisruptionEpisodes;
import dev.pti.api.insight.application.ListTicketingAnomalies;
import dev.pti.api.insight.application.SubmitDispatchFeedback;
import dev.pti.api.insight.application.port.BunchingReader;
import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.application.port.DispatchSuggestionReader;
import dev.pti.api.insight.application.port.DisruptionReader;
import dev.pti.api.insight.application.port.OtpReader;
import dev.pti.api.insight.application.port.TicketingAnomalyReader;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.platform.application.port.WriteMetrics;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases of the {@code insight} feature (DOC-32 §4): the reads run in the {@code readerTx} transaction,
 * the feedback in {@code operatorTx} (DOC-31 §10.1). The JDBC adapters are components: they name the caches and the
 * query timer of the platform in their constructors, which a {@code config} class may not (A-14).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
class InsightConfiguration {

    @Bean
    ListBunchingEpisodes listBunchingEpisodes(
            BunchingReader episodes, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListBunchingEpisodes(episodes, asOf, tx);
    }

    @Bean
    GetBunchingEpisode getBunchingEpisode(
            BunchingReader episodes, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetBunchingEpisode(episodes, asOf, tx);
    }

    @Bean
    ListDisruptionEpisodes listDisruptionEpisodes(
            DisruptionReader episodes, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListDisruptionEpisodes(episodes, asOf, tx);
    }

    @Bean
    GetDisruptionEpisode getDisruptionEpisode(
            DisruptionReader episodes, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetDisruptionEpisode(episodes, asOf, tx);
    }

    @Bean
    GetOtpScorecard getOtpScorecard(
            RequireActiveFeed requireActiveFeed,
            OtpReader scorecards,
            DataAsOfReader asOf,
            BusinessClock clock,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new GetOtpScorecard(requireActiveFeed, scorecards, asOf, clock, tx);
    }

    @Bean
    ListTicketingAnomalies listTicketingAnomalies(
            TicketingAnomalyReader anomalies, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListTicketingAnomalies(anomalies, asOf, tx);
    }

    @Bean
    GetTicketingAnomaly getTicketingAnomaly(
            TicketingAnomalyReader anomalies, DataAsOfReader asOf, @Qualifier("readerTx") TransactionRunner tx) {
        return new GetTicketingAnomaly(anomalies, asOf, tx);
    }

    @Bean
    ListDispatchSuggestions listDispatchSuggestions(
            DispatchSuggestionReader suggestions, @Qualifier("readerTx") TransactionRunner tx) {
        return new ListDispatchSuggestions(suggestions, tx);
    }

    @Bean
    SubmitDispatchFeedback submitDispatchFeedback(
            DispatchFeedbackStore store, WriteMetrics metrics, @Qualifier("operatorTx") TransactionRunner tx) {
        return new SubmitDispatchFeedback(store, metrics, tx);
    }
}
