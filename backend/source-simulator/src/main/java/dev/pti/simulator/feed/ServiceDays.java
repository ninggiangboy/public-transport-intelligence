package dev.pti.simulator.feed;

import dev.pti.common.gtfs.GtfsTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds and caches the {@link ServiceDay}s around the business clock (DOC-25 §3.3, §4). */
public final class ServiceDays {

    private static final Logger log = LoggerFactory.getLogger(ServiceDays.class);

    private final Feed feed;
    private final ServiceDateMapper mapper;
    private final ScheduleIndex index;
    private final VehicleAssigner assigner;
    private final Map<LocalDate, Optional<ServiceDay>> days = new ConcurrentHashMap<>();

    public ServiceDays(Feed feed, ServiceDateMapper mapper, Duration minLayover) {
        this.feed = feed;
        this.mapper = mapper;
        this.index = new ScheduleIndex(feed);
        this.assigner = new VehicleAssigner(feed.vehicleIds(), minLayover);
    }

    public Feed feed() {
        return feed;
    }

    /** The feed date whose timetable runs on {@code realDate}, if any (DOC-25 §3.2). */
    public Optional<LocalDate> feedDateFor(LocalDate realDate) {
        return mapper.feedDateFor(realDate);
    }

    public Optional<ServiceDay> day(LocalDate serviceDate) {
        return days.computeIfAbsent(serviceDate, this::build);
    }

    /**
     * The service days that can have trips running at {@code instant}: the agency-local date and the day before,
     * since GTFS times run past 24:00 (DOC-25 §3.3).
     */
    public List<ServiceDay> around(Instant instant) {
        LocalDate today = instant.atZone(feed.zone()).toLocalDate();
        List<ServiceDay> result = new ArrayList<>(2);
        day(today.minusDays(1)).ifPresent(result::add);
        day(today).ifPresent(result::add);
        days.keySet().removeIf(d -> d.isBefore(today.minusDays(2)) || d.isAfter(today.plusDays(2)));
        return result;
    }

    /** Seconds of {@code instant} counted from "noon minus 12h" of the service date (DOC-13 §3). */
    public long secondsOf(ServiceDay day, Instant instant) {
        return Duration.between(GtfsTime.toInstant(day.serviceDate(), 0, feed.zone()), instant)
                .toSeconds();
    }

    public Instant instantOf(LocalDate serviceDate, int seconds) {
        return GtfsTime.toInstant(serviceDate, seconds, feed.zone());
    }

    private Optional<ServiceDay> build(LocalDate serviceDate) {
        return mapper.feedDateFor(serviceDate).map(feedDate -> {
            List<Block> blocks = index.forFeedDate(feedDate);
            VehicleAssigner.Assignment assignment = assigner.assign(blocks, serviceDate);
            if (assignment.syntheticBuses() > 0) {
                log.warn(
                        "Not enough vehicles for {}: {} bus blocks use a synthetic BUS-<block_id>",
                        serviceDate,
                        assignment.syntheticBuses());
            }
            List<ServiceDay.AssignedBlock> assigned = blocks.stream()
                    .map(b -> new ServiceDay.AssignedBlock(
                            b, assignment.vehicleByBlock().get(b.blockId())))
                    .toList();
            return new ServiceDay(serviceDate, feedDate, assigned, assignment.syntheticBuses());
        });
    }
}
