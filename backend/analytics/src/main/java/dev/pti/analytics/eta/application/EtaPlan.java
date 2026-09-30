package dev.pti.analytics.eta.application;

import java.util.List;

/**
 * What a run of the ETA aggregation has to do (DOC-23 §7.2).
 *
 * @param status whether there is anything to do
 * @param routeIds the routes to aggregate, sorted; empty unless {@link Status#RUN}
 * @param watermark the fingerprint of the source data the plan was made for; empty when there is no feed
 */
public record EtaPlan(Status status, List<String> routeIds, String watermark) {

    public enum Status {
        /** No feed is ACTIVE: the schedule data the aggregation needs is missing (DOC-23 §15). */
        NO_FEED,
        /** The source data has not changed since the last completed run, and the run is not forced. */
        UP_TO_DATE,
        RUN
    }

    public EtaPlan {
        routeIds = List.copyOf(routeIds);
    }

    public static EtaPlan noFeed() {
        return new EtaPlan(Status.NO_FEED, List.of(), "");
    }

    public static EtaPlan upToDate(String watermark) {
        return new EtaPlan(Status.UP_TO_DATE, List.of(), watermark);
    }

    public static EtaPlan run(List<String> routeIds, String watermark) {
        return new EtaPlan(Status.RUN, routeIds, watermark);
    }
}
