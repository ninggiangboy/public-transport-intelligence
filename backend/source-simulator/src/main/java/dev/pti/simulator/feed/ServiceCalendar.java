package dev.pti.simulator.feed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** {@code calendar.txt} plus {@code calendar_dates.txt}: which services run on a date. */
public final class ServiceCalendar {

    private final Map<String, Weekly> weekly;
    private final Map<LocalDate, Map<String, Boolean>> exceptions;
    private final LocalDate validFrom;
    private final LocalDate validTo;

    ServiceCalendar(Map<String, Weekly> weekly, Map<LocalDate, Map<String, Boolean>> exceptions) {
        this.weekly = Map.copyOf(weekly);
        Map<LocalDate, Map<String, Boolean>> copy = new HashMap<>();
        exceptions.forEach((date, byService) -> copy.put(date, Map.copyOf(byService)));
        this.exceptions = Map.copyOf(copy);
        LocalDate from = LocalDate.MAX;
        LocalDate to = LocalDate.MIN;
        for (Weekly w : weekly.values()) {
            from = min(from, w.start());
            to = max(to, w.end());
        }
        for (Map.Entry<LocalDate, Map<String, Boolean>> e : exceptions.entrySet()) {
            if (e.getValue().containsValue(Boolean.TRUE)) {
                from = min(from, e.getKey());
                to = max(to, e.getKey());
            }
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("The feed has no service dates");
        }
        this.validFrom = from;
        this.validTo = to;
    }

    /** The service ids running on {@code date}, sorted. */
    public Set<String> activeServices(LocalDate date) {
        Set<String> active = new TreeSet<>();
        for (Map.Entry<String, Weekly> e : weekly.entrySet()) {
            if (e.getValue().runsOn(date)) {
                active.add(e.getKey());
            }
        }
        exceptions.getOrDefault(date, Map.of()).forEach((service, added) -> {
            if (added) {
                active.add(service);
            } else {
                active.remove(service);
            }
        });
        return active;
    }

    /** First date with service (DOC-13 §2.1, {@code valid_from}). */
    public LocalDate validFrom() {
        return validFrom;
    }

    /** Last date with service ({@code valid_to}). */
    public LocalDate validTo() {
        return validTo;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    /** One row of {@code calendar.txt}. */
    record Weekly(Set<DayOfWeek> days, LocalDate start, LocalDate end) {

        Weekly {
            days = Set.copyOf(days);
        }

        boolean runsOn(LocalDate date) {
            return !date.isBefore(start) && !date.isAfter(end) && days.contains(date.getDayOfWeek());
        }
    }
}
