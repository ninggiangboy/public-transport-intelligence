package dev.pti.etl.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.pti.testing.MetricCatalog;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** DOC-28 O-01 and O-02 for {@code etl-batch}: the catalog metrics registered at startup, and only their labels. */
class BatchMetricsIT extends BatchContextSupport {

    private static final MetricCatalog CATALOG = MetricCatalog.load("metric-catalog-batch.txt");

    @Autowired
    PrometheusMeterRegistry prometheus;

    @Test
    void exposesTheCatalogWithOnlyItsLabels() {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() ->
                        assertThat(CATALOG.missing(prometheus.scrape(), false)).isEmpty());
        assertThat(CATALOG.violations(prometheus.scrape())).isEmpty();
    }
}
