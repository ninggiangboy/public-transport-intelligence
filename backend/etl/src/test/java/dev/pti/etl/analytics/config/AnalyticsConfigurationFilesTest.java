package dev.pti.etl.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.analytics.config.AnalyticsProperties;
import dev.pti.analytics.config.AnalyticsPropertiesFixtures;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The {@code pti.analytics.*} defaults in {@code application*.yml} against the key table of DOC-23 §13. */
class AnalyticsConfigurationFilesTest {

    private static List<ConfigurationPropertySource> yaml(String... files) throws IOException {
        List<ConfigurationPropertySource> sources = new ArrayList<>();
        // The first file wins, as a profile file does over application.yml.
        for (String file : files) {
            for (PropertySource<?> source : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
                ConfigurationPropertySources.from(source).forEach(sources::add);
            }
        }
        return sources;
    }

    @Test
    void applicationYmlBindsToTheDocumentedDefaults() throws IOException {
        AnalyticsProperties bound = AnalyticsPropertiesFixtures.bind(yaml("application.yml"));

        assertThat(bound).isEqualTo(AnalyticsPropertiesFixtures.defaults());
    }

    @Test
    void theExecutorDefaultsAreTwoThreadsAndAThousandQueued() throws IOException {
        AnalyticsExecutorProperties executor = new Binder(yaml("application.yml"))
                .bind("pti.etl.analytics.executor", Bindable.of(AnalyticsExecutorProperties.class))
                .get();

        assertThat(executor).isEqualTo(new AnalyticsExecutorProperties(2, 1000));
    }

    @Test
    void theBaselineProfileTurnsAnalyticsOff() throws IOException {
        AnalyticsProperties bound =
                AnalyticsPropertiesFixtures.bind(yaml("application-experiment.yml", "application.yml"));

        assertThat(bound.enabled()).isFalse();
        assertThat(bound.bunching())
                .isEqualTo(AnalyticsPropertiesFixtures.defaults().bunching());
    }

    @Test
    void aBunchingCloseRatioBelowTheOpenRatioStopsStartup() {
        assertThatThrownBy(() -> AnalyticsPropertiesFixtures.bind(Map.of("bunching.close-ratio", "0.4")))
                .isInstanceOf(BindException.class)
                .hasStackTraceContaining("close-ratio must be greater than open-ratio");
    }
}
