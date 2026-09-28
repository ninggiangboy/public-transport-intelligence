package dev.pti.simulator.ticketing;

import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Route;
import dev.pti.simulator.feed.TripSchedule;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * The simulated sale points (DOC-25 §9.1): kiosks at the stops with the most departures on a reference weekday,
 * one onboard validator per bus route and three app channels. Picks a point with the weights of DOC-25 §9.3.
 */
public final class SalePointCatalog {

    /** Kiosk 45%, onboard 35%, app 20% (DOC-25 §9.3). */
    private static final double KIOSK_SHARE = 0.45;

    private static final double ONBOARD_SHARE = 0.35;

    private final List<SalePoint> all;
    private final Picker kiosks;
    private final Picker onboard;
    private final Picker apps;

    SalePointCatalog(List<SalePoint> kiosks, List<SalePoint> onboard, List<SalePoint> apps) {
        List<SalePoint> list = new ArrayList<>(kiosks);
        list.addAll(onboard);
        list.addAll(apps);
        this.all = List.copyOf(list);
        this.kiosks = new Picker(kiosks);
        this.onboard = new Picker(onboard);
        this.apps = new Picker(apps);
    }

    /**
     * Builds the catalog from the trips running on {@code referenceDate}, a weekday of the feed.
     *
     * @param kioskCount {@code pti.sim.ticketing.kiosk-count}
     */
    public static SalePointCatalog build(Feed feed, LocalDate referenceDate, int kioskCount) {
        Set<String> services = feed.calendar().activeServices(referenceDate);
        Map<String, Integer> departuresByStop = new HashMap<>();
        Map<String, Integer> tripsByRoute = new HashMap<>();
        for (TripSchedule trip : feed.trips().values()) {
            if (!services.contains(trip.serviceId())) {
                continue;
            }
            tripsByRoute.merge(trip.routeId(), 1, Integer::sum);
            for (int i = 0; i < trip.lastIndex(); i++) {
                departuresByStop.merge(trip.stop(i).id(), 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> busiest = new ArrayList<>(departuresByStop.entrySet());
        busiest.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        List<SalePoint> kiosks = new ArrayList<>();
        for (int k = 0; k < Math.min(kioskCount, busiest.size()); k++) {
            Map.Entry<String, Integer> stop = busiest.get(k);
            kiosks.add(new SalePoint(
                    "KIOSK-%03d".formatted(k + 1),
                    "Kiosk – " + feed.stops().get(stop.getKey()).name(),
                    SalePoint.Kind.KIOSK,
                    stop.getKey(),
                    null,
                    stop.getValue()));
        }

        List<SalePoint> onboard = feed.routes().values().stream()
                .filter(route -> !route.isRail())
                .sorted(Comparator.comparing(Route::id))
                .map(route -> new SalePoint(
                        "ONBOARD-" + route.id(),
                        "Onboard validator – Route " + route.displayName(),
                        SalePoint.Kind.ONBOARD,
                        null,
                        route.id(),
                        tripsByRoute.getOrDefault(route.id(), 0)))
                .toList();

        List<SalePoint> apps = List.of(
                new SalePoint("APP-IOS", "Mobile app (iOS)", SalePoint.Kind.APP, null, null, 0.45),
                new SalePoint("APP-ANDROID", "Mobile app (Android)", SalePoint.Kind.APP, null, null, 0.45),
                new SalePoint("APP-WEB", "Web store", SalePoint.Kind.APP, null, null, 0.10));
        return new SalePointCatalog(kiosks, onboard, apps);
    }

    public List<SalePoint> all() {
        return all;
    }

    /** A sale point for the next sale. */
    public SalePoint pick(RandomGenerator rng) {
        double u = rng.nextDouble();
        if (u < KIOSK_SHARE && !kiosks.isEmpty()) {
            return kiosks.pick(rng);
        }
        if (u < KIOSK_SHARE + ONBOARD_SHARE && !onboard.isEmpty()) {
            return onboard.pick(rng);
        }
        return apps.pick(rng);
    }

    /** Picks by weight with a binary search over cumulative weights. */
    private static final class Picker {

        private final List<SalePoint> points;
        private final double[] cumulative;

        Picker(List<SalePoint> points) {
            this.points = List.copyOf(points);
            this.cumulative = new double[points.size()];
            double sum = 0;
            for (int i = 0; i < points.size(); i++) {
                sum += points.get(i).weight();
                cumulative[i] = sum;
            }
        }

        boolean isEmpty() {
            return points.isEmpty() || cumulative[cumulative.length - 1] <= 0;
        }

        SalePoint pick(RandomGenerator rng) {
            double target = rng.nextDouble() * cumulative[cumulative.length - 1];
            int lo = 0;
            int hi = cumulative.length - 1;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (cumulative[mid] > target) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            return points.get(lo);
        }
    }
}
