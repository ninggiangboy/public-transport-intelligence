package dev.pti.simulator.ticketing;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.SimProperties;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.TickLoop;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.rate.RateControl;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the TicketingSeeder (DOC-25 §9). */
@Configuration(proxyBeanMethods = false)
public class TicketingConfiguration {

    @Bean
    SalePointCatalog salePointCatalog(Feed feed, SimProperties properties) {
        return SalePointCatalog.build(
                feed, referenceWeekday(feed), properties.ticketing().kioskCount());
    }

    @Bean
    TicketingSeeder ticketingSeeder(
            BusinessClock clock,
            Feed feed,
            SalePointCatalog catalog,
            @Qualifier("ticketingJdbcTemplate") JdbcTemplate jdbc,
            RateControl rate,
            SimProperties properties,
            MeterRegistry registry,
            Throughput throughput) {
        SaleGenerator generator = new SaleGenerator(catalog, properties.ticketing(), feed.zone(), properties.seed());
        return new TicketingSeeder(
                clock,
                generator,
                catalog,
                new TicketingRepository(jdbc),
                rate,
                properties.ticketing(),
                properties.seed(),
                registry,
                throughput);
    }

    @Bean
    TickLoop ticketingLoop(TicketingSeeder seeder, SimProperties properties) {
        return new TickLoop("sim-ticketing", seeder::tick, properties.tick());
    }

    /**
     * The weekday whose departures place the kiosks: the first Tuesday of the feed, mapped like any real date
     * (2026-09-29 for the pinned feed, DOC-25 §9.1).
     */
    static LocalDate referenceWeekday(Feed feed) {
        LocalDate tuesday = feed.validFrom().with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));
        return new ServiceDateMapper(feed.calendar(), "auto")
                .feedDateFor(tuesday)
                .orElse(tuesday);
    }
}
