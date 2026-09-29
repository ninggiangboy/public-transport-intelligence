package dev.pti.simulator.control;

import dev.pti.common.time.BusinessClock;
import dev.pti.simulator.Throughput;
import dev.pti.simulator.emit.Emitter;
import dev.pti.simulator.emit.MessageFactory;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.ledger.Ledger;
import dev.pti.simulator.rate.RateControl;
import dev.pti.simulator.scenario.ScenarioEngine;
import dev.pti.simulator.ticketing.TicketingSeeder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Assembles {@link SimStatus} from the running components. */
@Service
public class StatusService {

    private static final List<String> TOPICS = List.of(MessageFactory.VEHICLE_POSITIONS, MessageFactory.TRIP_UPDATES);

    private final BusinessClock clock;
    private final Feed feed;
    private final ServiceDays serviceDays;
    private final RateControl rate;
    private final Emitter emitter;
    private final Throughput throughput;
    private final Ledger ledger;
    private final ScenarioEngine scenarios;

    public StatusService(
            BusinessClock clock,
            Feed feed,
            ServiceDays serviceDays,
            RateControl rate,
            Emitter emitter,
            Throughput throughput,
            Ledger ledger,
            ScenarioEngine scenarios) {
        this.clock = clock;
        this.feed = feed;
        this.serviceDays = serviceDays;
        this.rate = rate;
        this.emitter = emitter;
        this.throughput = throughput;
        this.ledger = ledger;
        this.scenarios = scenarios;
    }

    public SimStatus status() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(feed.zone()).toLocalDate();
        List<SimStatus.ServiceDate> serviceDates = List.of(serviceDate(today.minusDays(1)), serviceDate(today));
        Map<String, Double> messagesPerSecond = new LinkedHashMap<>();
        TOPICS.forEach(topic -> messagesPerSecond.put(topic, throughput.perSecond(topic)));
        return new SimStatus(
                new SimStatus.Clock(now, clock.offset().toString(), feed.zone().getId(), serviceDates),
                new SimStatus.Feed(feed.sha256(), feed.validFrom(), feed.validTo()),
                new SimStatus.Rate(rate.gtfsRt(), rate.ticketing()),
                emitter.activeVehicles(),
                emitter.activeTrips(),
                messagesPerSecond,
                throughput.perSecond(TicketingSeeder.SALES),
                new SimStatus.Ledger(ledger.queueDepth(), ledger.lastFlushAt()),
                scenarios.running().stream()
                        .map(r -> new SimStatus.RunningScenario(r.runId().toString(), r.scenario(), r.plannedEndAt()))
                        .toList());
    }

    private SimStatus.ServiceDate serviceDate(LocalDate realDate) {
        return new SimStatus.ServiceDate(
                realDate, serviceDays.feedDateFor(realDate).orElse(null));
    }
}
