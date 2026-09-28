package dev.pti.simulator.feed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** The blocks of each feed date, sorted by {@code (start, block_id)} (DOC-25 §4.2). */
public final class ScheduleIndex {

    private static final Comparator<Block> ORDER =
            Comparator.comparingInt(Block::start).thenComparing(Block::blockId);

    private final Feed feed;
    private final Map<LocalDate, List<Block>> byFeedDate = new ConcurrentHashMap<>();

    public ScheduleIndex(Feed feed) {
        this.feed = feed;
    }

    public List<Block> forFeedDate(LocalDate feedDate) {
        return byFeedDate.computeIfAbsent(feedDate, this::build);
    }

    private List<Block> build(LocalDate feedDate) {
        Set<String> services = feed.calendar().activeServices(feedDate);
        Map<String, List<TripSchedule>> trips = new TreeMap<>();
        for (TripSchedule trip : feed.trips().values()) {
            if (services.contains(trip.serviceId())) {
                trips.computeIfAbsent(trip.blockId(), k -> new ArrayList<>()).add(trip);
            }
        }
        List<Block> blocks = new ArrayList<>(trips.size());
        trips.forEach((blockId, list) -> {
            list.sort(Comparator.comparingInt(TripSchedule::firstDeparture).thenComparing(TripSchedule::tripId));
            int start =
                    list.stream().mapToInt(TripSchedule::firstDeparture).min().orElseThrow();
            int end = list.stream().mapToInt(TripSchedule::lastArrival).max().orElseThrow();
            boolean rail = list.stream().allMatch(t -> t.route().isRail());
            blocks.add(new Block(blockId, list, start, end, rail));
        });
        blocks.sort(ORDER);
        return List.copyOf(blocks);
    }
}
