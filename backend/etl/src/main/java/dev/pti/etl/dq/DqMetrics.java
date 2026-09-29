package dev.pti.etl.dq;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The post-write gauges of DOC-16 §4, read from {@code ops.dq_check_result} every minute so they are right after a
 * restart too. A rule turned off has no gauge, so turning it off does not fire {@code DataQualityCheckStale}.
 */
public class DqMetrics {

    private static final Logger log = LoggerFactory.getLogger(DqMetrics.class);

    private final DqCheckResults results;
    private final Map<String, DqCheckResults.Latest> latest = new ConcurrentHashMap<>();

    public DqMetrics(DqCheckResults results, MeterRegistry meters, Predicate<String> enabled) {
        this.results = results;
        for (PostWriteRule rule : PostWriteRule.values()) {
            if (!enabled.test(rule.id())) {
                continue;
            }
            Gauge.builder("pti.dq.check.violations", () -> value(rule, l -> (double) l.violations()))
                    .tag("rule", rule.id())
                    .register(meters);
            Gauge.builder(
                            "pti.dq.check.breached",
                            () -> value(rule, l -> rule.breached(l.violations(), l.population()) ? 1.0 : 0.0))
                    .tag("rule", rule.id())
                    .register(meters);
            Gauge.builder(
                            "pti.dq.check.last.run.timestamp.seconds",
                            () -> value(rule, l -> (double) l.checkedAt().getEpochSecond()))
                    .tag("rule", rule.id())
                    .register(meters);
            Gauge.builder("pti.dq.check.interval.seconds", () ->
                            (double) rule.interval().toSeconds())
                    .tag("rule", rule.id())
                    .register(meters);
        }
    }

    private double value(PostWriteRule rule, java.util.function.ToDoubleFunction<DqCheckResults.Latest> f) {
        DqCheckResults.Latest l = latest.get(rule.id());
        return l == null ? Double.NaN : f.applyAsDouble(l);
    }

    @Scheduled(initialDelay = 0, fixedDelay = 60_000, scheduler = "ptiTaskScheduler")
    public void refresh() {
        try {
            latest.putAll(results.latest());
        } catch (RuntimeException e) {
            log.warn("Cannot read the data quality results: {}", e.toString());
        }
    }
}
