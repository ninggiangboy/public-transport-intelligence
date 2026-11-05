package dev.pti.simulator.ticketing.config;

import dev.pti.simulator.config.SimProperties;
import dev.pti.simulator.feed.domain.Feed;
import dev.pti.simulator.feed.domain.ServiceDateMapper;
import dev.pti.simulator.ticketing.domain.SalePointCatalog;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the ticketing sale points (DOC-25 §9). */
@Configuration(proxyBeanMethods = false)
public class TicketingConfiguration {

    @Bean
    SalePointCatalog salePointCatalog(Feed feed, SimProperties properties) {
        return SalePointCatalog.build(
                feed, referenceWeekday(feed), properties.ticketing().kioskCount());
    }

    /**
     * The weekday whose departures place the kiosks: the first Tuesday of the feed, mapped like any real date
     * (2026-09-29 for the pinned feed, DOC-25 §9.1).
     */
    public static LocalDate referenceWeekday(Feed feed) {
        LocalDate tuesday = feed.validFrom().with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        return new ServiceDateMapper(feed.calendar(), "auto")
                .feedDateFor(tuesday)
                .orElse(tuesday);
    }
}
