package dev.pti.etl.analytics.application;

import dev.pti.analytics.core.application.RouteDetector;
import dev.pti.analytics.core.application.port.AnalyticsMetrics;
import dev.pti.analytics.core.application.port.OpenEpisodeCounts;
import dev.pti.analytics.core.domain.Detector;
import dev.pti.analytics.core.domain.Outcome;
import dev.pti.analytics.core.domain.Trigger;
import dev.pti.analytics.event.application.port.AnalyticsEventSink;
import dev.pti.common.time.BusinessClock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 30 second tick (DOC-23 §4.2): advance every route that still holds state, so that an episode closes on a route
 * that has gone quiet, and refresh the open-episode gauges from the database. The state is in the database, so every
 * pod can run the tick; the advisory lock lets one pod handle a route at a time.
 */
public class RunAnalyticsTick {

    private static final Logger log = LoggerFactory.getLogger(RunAnalyticsTick.class);

    private static final List<Detector> EPISODE_DETECTORS = List.of(Detector.BUNCHING, Detector.DISRUPTION);

    private final List<RouteDetector> detectors;
    private final AnalyticsMetrics metrics;
    private final OpenEpisodeCounts openEpisodes;
    private final SafeAdvance safeAdvance;

    public RunAnalyticsTick(
            List<RouteDetector> detectors,
            AnalyticsMetrics metrics,
            OpenEpisodeCounts openEpisodes,
            AnalyticsEventSink sink,
            BusinessClock clock) {
        this.detectors = List.copyOf(detectors);
        this.metrics = metrics;
        this.openEpisodes = openEpisodes;
        this.safeAdvance = new SafeAdvance(metrics, sink, clock);
    }

    public DispatchSummary execute() {
        refreshOpenEpisodes();
        int runs = 0;
        int errors = 0;
        for (RouteDetector detector : detectors) {
            List<String> routes;
            try {
                routes = detector.routesNeedingTick();
            } catch (RuntimeException e) {
                metrics.run(detector.detector(), Trigger.TICK, Outcome.ERROR);
                log.error(
                        "analytics run failed: cannot list the routes that need a tick of {}", detector.detector(), e);
                errors++;
                continue;
            }
            for (String routeId : routes) {
                runs++;
                if (safeAdvance.run(detector, routeId, Trigger.TICK, null) == Outcome.ERROR) {
                    errors++;
                }
            }
        }
        return new DispatchSummary(runs, errors);
    }

    private void refreshOpenEpisodes() {
        for (Detector detector : EPISODE_DETECTORS) {
            try {
                metrics.openEpisodes(detector, openEpisodes.count(detector));
            } catch (RuntimeException e) {
                log.warn("Cannot count the open episodes of {}", detector, e);
            }
        }
    }
}
