package dev.pti.etl.reference;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code calendar} and {@code calendar_dates} of one feed version in memory; the Java twin of
 * {@code dw.service_ids_on} (DOC-14 §5).
 */
public final class ServiceCalendar {

    /** One {@code calendar.txt} row; {@code days[0]} is Monday. */
    public record Weekly(String serviceId, boolean[] days, LocalDate start, LocalDate end) {

        public Weekly {
            days = days.clone();
        }

        boolean runsOn(LocalDate date) {
            return !date.isBefore(start)
                    && !date.isAfter(end)
                    && days[date.getDayOfWeek().getValue() - 1];
        }
    }

    /** One {@code calendar_dates.txt} row: type 1 adds the date, type 2 removes it. */
    public record DateChange(String serviceId, LocalDate date, int type) {}

    private final List<Weekly> weekly;
    private final Map<LocalDate, List<DateChange>> changes = new HashMap<>();

    public ServiceCalendar(List<Weekly> weekly, List<DateChange> changes) {
        this.weekly = List.copyOf(weekly);
        for (DateChange c : changes) {
            this.changes.computeIfAbsent(c.date(), d -> new ArrayList<>()).add(c);
        }
    }

    public Set<String> serviceIdsOn(LocalDate date) {
        Set<String> removed = new HashSet<>();
        Set<String> result = new HashSet<>();
        for (DateChange c : changes.getOrDefault(date, List.of())) {
            if (c.type() == 2) {
                removed.add(c.serviceId());
            } else if (c.type() == 1) {
                result.add(c.serviceId());
            }
        }
        for (Weekly w : weekly) {
            if (w.runsOn(date) && !removed.contains(w.serviceId())) {
                result.add(w.serviceId());
            }
        }
        return Set.copyOf(result);
    }
}
