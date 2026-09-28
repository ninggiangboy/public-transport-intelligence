package dev.pti.simulator.feed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a real service date to the feed date whose schedule is run (DR-08, DOC-25 §3.2). Dates in messages stay
 * real; the mapping only picks trips.
 */
public final class ServiceDateMapper {

    private static final Logger log = LoggerFactory.getLogger(ServiceDateMapper.class);
    private static final String FIXED = "fixed:";

    private final ServiceCalendar calendar;
    private final LocalDate fixed;
    private final Map<LocalDate, Optional<LocalDate>> cache = new ConcurrentHashMap<>();

    /**
     * @param mapping {@code auto} or {@code fixed:<YYYY-MM-DD>} ({@code pti.sim.service-date-mapping})
     * @throws IllegalArgumentException for any other value
     */
    public ServiceDateMapper(ServiceCalendar calendar, String mapping) {
        this.calendar = calendar;
        if ("auto".equals(mapping)) {
            this.fixed = null;
        } else if (mapping.startsWith(FIXED)) {
            this.fixed = LocalDate.parse(mapping.substring(FIXED.length()));
        } else {
            throw new IllegalArgumentException(
                    "pti.sim.service-date-mapping must be 'auto' or 'fixed:<YYYY-MM-DD>': " + mapping);
        }
    }

    /** The feed date to run for {@code realDate}; empty when no date of the feed has that day of the week. */
    public Optional<LocalDate> feedDateFor(LocalDate realDate) {
        if (fixed != null) {
            return Optional.of(fixed);
        }
        return cache.computeIfAbsent(realDate, this::map);
    }

    private Optional<LocalDate> map(LocalDate realDate) {
        LocalDate from = calendar.validFrom();
        LocalDate to = calendar.validTo();
        if (!realDate.isBefore(from) && !realDate.isAfter(to)) {
            return Optional.of(realDate);
        }
        DayOfWeek day = realDate.getDayOfWeek();
        // Group the same weekdays of the feed by their set of services; the largest group is the regular
        // schedule of that weekday and leaves holidays out. Groups keep the order of their earliest date.
        Map<Set<String>, List<LocalDate>> groups = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == day) {
                Set<String> services = calendar.activeServices(d);
                if (!services.isEmpty()) {
                    groups.computeIfAbsent(services, k -> new ArrayList<>()).add(d);
                }
            }
        }
        Map.Entry<Set<String>, List<LocalDate>> best = null;
        for (Map.Entry<Set<String>, List<LocalDate>> group : groups.entrySet()) {
            if (best == null || group.getValue().size() > best.getValue().size()) {
                best = group;
            }
        }
        if (best == null) {
            log.error("Service date mapping: real={} has no {} in the feed; nothing runs that day", realDate, day);
            return Optional.empty();
        }
        LocalDate feedDate = best.getValue().getFirst();
        log.info(
                "Service date mapping: real={} feed={} ({}, {} service_ids)",
                realDate,
                feedDate,
                day,
                best.getKey().size());
        return Optional.of(feedDate);
    }
}
