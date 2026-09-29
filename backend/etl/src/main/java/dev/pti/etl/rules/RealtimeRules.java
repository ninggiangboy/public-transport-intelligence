package dev.pti.etl.rules;

import dev.pti.common.dq.DlqStage;
import dev.pti.etl.config.DqProperties;
import dev.pti.etl.reference.ReferenceData;
import dev.pti.etl.reference.TripRef;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The per-record rules of GTFS-realtime messages, DQ-03 to DQ-09 (DOC-16 §2). */
public final class RealtimeRules {

    private RealtimeRules() {}

    public static List<RecordRule<RealtimeFacts>> all(DqProperties properties) {
        return List.of(
                new RouteExists(),
                new StopsExist(),
                new TripMatches(),
                new InServiceArea(properties.bboxMargin()),
                new PlausibleEventTime(properties.maxClockSkew()),
                new PlausibleDelay(properties.maxDelay()),
                new ServiceDateMatches());
    }

    private abstract static class Quality implements RecordRule<RealtimeFacts> {

        @Override
        public DlqStage stage() {
            return DlqStage.QUALITY;
        }

        @Override
        public boolean appliesDuringReplay() {
            return true;
        }
    }

    /** DQ-03: the route exists in the ACTIVE feed. */
    static final class RouteExists extends Quality {

        @Override
        public String id() {
            return "DQ-03";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            ReferenceData ref = context.requireReference();
            return ref.hasRoute(r.routeId())
                    ? Optional.empty()
                    : Optional.of("Route " + r.routeId() + " not found in feed version " + ref.feedVersionId());
        }
    }

    /** DQ-04: every stop exists in the ACTIVE feed. */
    static final class StopsExist extends Quality {

        @Override
        public String id() {
            return "DQ-04";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            ReferenceData ref = context.requireReference();
            return r.stopIds().stream()
                    .filter(stop -> !ref.hasStop(stop))
                    .findFirst()
                    .map(stop -> "Stop " + stop + " not found in feed version " + ref.feedVersionId());
        }
    }

    /** DQ-05: the trip exists and belongs to the record's route and direction. */
    static final class TripMatches extends Quality {

        @Override
        public String id() {
            return "DQ-05";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            ReferenceData ref = context.requireReference();
            Optional<TripRef> trip = ref.trip(r.tripId());
            if (trip.isEmpty()) {
                return Optional.of("Trip " + r.tripId() + " not found in feed version " + ref.feedVersionId());
            }
            TripRef t = trip.get();
            if (!t.routeId().equals(r.routeId()) || t.directionId() != r.directionId()) {
                return Optional.of("Trip %s runs on route %s direction %d, not route %s direction %d"
                        .formatted(r.tripId(), t.routeId(), t.directionId(), r.routeId(), r.directionId()));
            }
            return Optional.empty();
        }
    }

    /** DQ-06: the position lies inside the feed's bounding box plus a margin. */
    static final class InServiceArea extends Quality {

        private final double margin;

        InServiceArea(double margin) {
            this.margin = margin;
        }

        @Override
        public String id() {
            return "DQ-06";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            Double lat = r.lat();
            Double lon = r.lon();
            if (lat == null || lon == null) {
                return Optional.empty();
            }
            return context.requireReference().bbox().contains(lat, lon, margin)
                    ? Optional.empty()
                    : Optional.of("Position %.6f,%.6f is outside the service area".formatted(lat, lon));
        }
    }

    /** DQ-07: event time within {@code pti.dq.max-clock-skew} of the business clock; skipped on replay. */
    static final class PlausibleEventTime extends Quality {

        private final Duration maxSkew;

        PlausibleEventTime(Duration maxSkew) {
            this.maxSkew = maxSkew;
        }

        @Override
        public String id() {
            return "DQ-07";
        }

        @Override
        public boolean appliesDuringReplay() {
            return false;
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            Duration skew = Duration.between(context.businessNow(), r.eventTimestamp());
            return skew.abs().compareTo(maxSkew) > 0
                    ? Optional.of("Event time %s is %ds from business time %s"
                            .formatted(r.eventTimestamp(), skew.toSeconds(), context.businessNow()))
                    : Optional.empty();
        }
    }

    /** DQ-08: every delay within {@code ±pti.dq.max-delay}. */
    static final class PlausibleDelay extends Quality {

        private final long maxSeconds;

        PlausibleDelay(Duration maxDelay) {
            this.maxSeconds = maxDelay.toSeconds();
        }

        @Override
        public String id() {
            return "DQ-08";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            return r.delays().stream()
                    .filter(d -> Math.abs((long) d) > maxSeconds)
                    .findFirst()
                    .map(d -> "Delay " + d + "s exceeds " + maxSeconds + "s");
        }
    }

    /** DQ-09: the service date is the day of the event, or the day before for trips past midnight. */
    static final class ServiceDateMatches extends Quality {

        @Override
        public String id() {
            return "DQ-09";
        }

        @Override
        public Optional<String> check(RealtimeFacts r, RuleContext context) {
            LocalDate eventDay = LocalDate.ofInstant(
                    r.eventTimestamp(), context.requireReference().agencyZone());
            boolean ok = r.serviceDate().equals(eventDay) || r.serviceDate().equals(eventDay.minusDays(1));
            return ok
                    ? Optional.empty()
                    : Optional.of("Service date %s does not match event day %s".formatted(r.serviceDate(), eventDay));
        }
    }
}
