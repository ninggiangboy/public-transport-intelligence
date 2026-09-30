package dev.pti.analytics.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

/**
 * The key table of DOC-23 §13 with its defaults, and a binder that validates like Spring Boot does at startup. A test
 * overrides single keys to see a rule fire; the {@code application.yml} of the app that runs analytics must bind to
 * the same values as {@link #defaults()}.
 */
public final class AnalyticsPropertiesFixtures {

    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory();

    private AnalyticsPropertiesFixtures() {}

    /** Every key of DOC-23 §13 under {@code pti.analytics} with its default. */
    public static Map<String, String> defaultKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        put(keys, "enabled", "true");
        put(keys, "dispatcher.tick-interval", "30s");

        put(keys, "bunching.enabled", "true");
        put(keys, "bunching.route-types", "3");
        put(keys, "bunching.evaluation-interval", "15s");
        put(keys, "bunching.allowed-lateness", "5s");
        put(keys, "bunching.idle-timeout", "60s");
        put(keys, "bunching.position-max-age", "120s");
        put(keys, "bunching.open-ratio", "0.5");
        put(keys, "bunching.close-ratio", "0.7");
        put(keys, "bunching.open-consecutive", "2");
        put(keys, "bunching.exclude-first-stops", "2");
        put(keys, "bunching.exclude-last-stops", "2");
        put(keys, "bunching.max-headway", "30m");
        put(keys, "bunching.leader-lookback", "30m");
        put(keys, "bunching.max-catch-up", "15m");

        put(keys, "disruption.enabled", "true");
        put(keys, "disruption.route-types", "0,3");
        put(keys, "disruption.bucket", "1m");
        put(keys, "disruption.window", "10m");
        put(keys, "disruption.min-samples", "5");
        put(keys, "disruption.alpha", "0.1");
        put(keys, "disruption.sigma-floor", "30s");
        put(keys, "disruption.open-z", "2.5");
        put(keys, "disruption.open-consecutive", "2");
        put(keys, "disruption.close-z", "1.5");
        put(keys, "disruption.close-consecutive", "3");
        put(keys, "disruption.warm-up-buckets", "60");
        put(keys, "disruption.max-episode-duration", "3h");
        put(keys, "disruption.severity-high-z", "4.0");
        put(keys, "disruption.allowed-lateness", "10s");
        put(keys, "disruption.idle-timeout", "60s");
        put(keys, "disruption.max-catch-up", "60m");

        put(keys, "eta.window", "28d");
        put(keys, "eta.confidence.medium-min", "10");
        put(keys, "eta.confidence.high-min", "30");
        put(keys, "eta.realtime-enabled", "false");
        put(keys, "eta.realtime-max-age", "2m");
        put(keys, "eta.arrivals.default-limit", "10");
        put(keys, "eta.arrivals.horizon", "90m");

        put(keys, "otp.early-tolerance", "300s");
        put(keys, "otp.late-tolerance", "300s");
        put(keys, "otp.recompute-days", "2");

        put(keys, "ticketing.window", "15m");
        put(keys, "ticketing.allowed-lateness", "2m");
        put(keys, "ticketing.baseline-weeks", "4");
        put(keys, "ticketing.min-baseline-windows", "8");
        put(keys, "ticketing.cold-start-windows", "8");
        put(keys, "ticketing.volume-z", "3.0");
        put(keys, "ticketing.volume-min-txn", "20");
        put(keys, "ticketing.refund-ratio", "0.3");
        put(keys, "ticketing.refund-min-count", "5");
        put(keys, "ticketing.max-catch-up", "24h");
        return keys;
    }

    /** The defaults, bound and validated. */
    public static AnalyticsProperties defaults() {
        return bind(Map.of());
    }

    /**
     * Binds and validates the defaults with some keys replaced.
     *
     * @param overrides keys relative to {@code pti.analytics}, for example {@code bunching.close-ratio}
     * @throws org.springframework.boot.context.properties.bind.BindException when a value is rejected
     */
    public static AnalyticsProperties bind(Map<String, String> overrides) {
        Map<String, String> keys = defaultKeys();
        overrides.forEach((key, value) -> put(keys, key, value));
        return bind(List.of(new MapConfigurationPropertySource(keys)));
    }

    /** Binds and validates the {@code pti.analytics} keys of any property sources. */
    public static AnalyticsProperties bind(Iterable<ConfigurationPropertySource> sources) {
        Validator validator = FACTORY.getValidator();
        return new Binder(sources)
                .bind(
                        "pti.analytics",
                        Bindable.of(AnalyticsProperties.class),
                        new ValidationBindHandler(new SpringValidatorAdapter(validator)))
                .get();
    }

    private static void put(Map<String, String> keys, String key, String value) {
        keys.put("pti.analytics." + key, value);
    }
}
