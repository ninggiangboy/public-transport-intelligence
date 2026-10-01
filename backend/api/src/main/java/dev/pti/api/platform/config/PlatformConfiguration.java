package dev.pti.api.platform.config;

import com.github.benmanes.caffeine.cache.Cache;
import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter;
import dev.pti.api.platform.adapter.in.web.ApiRequestFilter;
import dev.pti.api.platform.adapter.in.web.BodySizeLimitFilter;
import dev.pti.api.platform.adapter.in.web.CallerArgumentResolver;
import dev.pti.api.platform.adapter.in.web.CallerFactory;
import dev.pti.api.platform.adapter.in.web.CursorCodec;
import dev.pti.api.platform.adapter.in.web.PageParams;
import dev.pti.api.platform.adapter.in.web.ProblemFactory;
import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.api.platform.adapter.in.web.TimeRanges;
import dev.pti.api.platform.adapter.in.web.UnknownQueryParameterInterceptor;
import dev.pti.api.platform.adapter.out.cache.ApiCaches;
import dev.pti.api.platform.adapter.out.cache.CachingActiveFeedReader;
import dev.pti.api.platform.adapter.out.jdbc.JdbcActiveFeedReader;
import dev.pti.api.platform.adapter.out.jdbc.QueryMetrics;
import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.ActiveFeedReader;
import dev.pti.api.platform.domain.ActiveFeed;
import dev.pti.common.error.ErrorClassifier;
import dev.pti.common.time.BusinessClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform's shared infrastructure (DOC-49 §11.2): Problem Details, paging, the request filters, rate limits,
 * caches, the ACTIVE feed and the MVC hooks that every controller relies on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiProperties.class)
class PlatformConfiguration implements WebMvcConfigurer {

    /** Request body nesting and string limits (DOC-27 §9). */
    private static final int MAX_NESTING_DEPTH = 64;

    private static final int MAX_STRING_LENGTH = 1024 * 1024;

    // Filters are registered here and are not beans of type Filter, so that Boot does not add them a second time.
    private static final int API_REQUEST_FILTER_ORDER = -200;
    private static final int BODY_SIZE_FILTER_ORDER = -150;

    private final CallerFactory callerFactory;
    private final UnknownQueryParameterInterceptor unknownParameters = new UnknownQueryParameterInterceptor();

    PlatformConfiguration(RoleHierarchy hierarchy) {
        this.callerFactory = new CallerFactory(hierarchy);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(unknownParameters);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver(callerFactory));
    }

    /** Request bodies nest at most 64 levels and hold strings of at most 1 MiB (DOC-27 §9). */
    @Bean
    JsonFactoryBuilderCustomizer requestLimits() {
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .build());
    }

    @Bean
    ProblemFactory problemFactory(MeterRegistry registry) {
        return new ProblemFactory(registry);
    }

    @Bean
    ProblemWriter problemWriter(ProblemFactory problems, JsonMapper mapper) {
        return new ProblemWriter(problems, mapper);
    }

    @Bean
    ErrorClassifier errorClassifier() {
        return new ErrorClassifier();
    }

    @Bean
    CursorCodec cursorCodec(JsonMapper mapper) {
        return new CursorCodec(mapper);
    }

    @Bean
    PageParams pageParams(ApiProperties properties, CursorCodec codec) {
        return new PageParams(
                properties.paging().defaultLimit(), properties.paging().maxLimit(), codec);
    }

    /** Ranges of event-time parameters ({@code from}/{@code to} of insights), "now" being business time (DOC-31 §4.2). */
    @Bean
    TimeRanges eventTimeRanges(ApiProperties properties, BusinessClock clock) {
        return new TimeRanges(clock::instant, properties.time().maxRange());
    }

    /** Ranges of audit-time parameters (alerts, jobs, dead letters), "now" being real time (DOC-31 §4.2). */
    @Bean
    TimeRanges auditTimeRanges(ApiProperties properties, BusinessClock clock) {
        return new TimeRanges(clock::realNow, properties.time().maxRange());
    }

    @Bean
    FilterRegistrationBean<ApiRequestFilter> apiRequestFilter(ProblemWriter problems) {
        FilterRegistrationBean<ApiRequestFilter> registration =
                new FilterRegistrationBean<>(new ApiRequestFilter(problems));
        registration.setOrder(API_REQUEST_FILTER_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<BodySizeLimitFilter> bodySizeLimitFilter(ApiProperties properties, ProblemWriter problems) {
        FilterRegistrationBean<BodySizeLimitFilter> registration = new FilterRegistrationBean<>(
                new BodySizeLimitFilter(properties.maxBodySize().toBytes(), problems));
        registration.setOrder(BODY_SIZE_FILTER_ORDER);
        return registration;
    }

    @Bean
    @ConditionalOnProperty(name = "pti.api.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    RateLimiter rateLimiter(ApiProperties properties, MeterRegistry registry) {
        ApiProperties.RateLimit limits = properties.rateLimit();
        return new RateLimiter(
                new RateLimiter.Limits(
                        limits.publicPerMinute(), limits.authenticatedPerMinute(), limits.writePerMinute()),
                registry);
    }

    @Bean
    ApiCaches apiCaches(ApiProperties properties, MeterRegistry registry) {
        Map<String, ApiCaches.Spec> specs = new LinkedHashMap<>();
        properties.cache().forEach((name, spec) -> specs.put(name, new ApiCaches.Spec(spec.ttl(), spec.maxSize())));
        return new ApiCaches(specs, registry);
    }

    @Bean
    QueryMetrics queryMetrics(MeterRegistry registry) {
        return new QueryMetrics(registry);
    }

    /** The ACTIVE feed, cached 30 seconds; a change of feed clears the GTFS caches (DOC-31 §10.2, §10.3). */
    @Bean
    ActiveFeedReader activeFeedReader(@Qualifier("reader") JdbcClient reader, QueryMetrics metrics, ApiCaches caches) {
        Cache<String, Optional<ActiveFeed>> cache = caches.cache("active-feed");
        return new CachingActiveFeedReader(new JdbcActiveFeedReader(reader, metrics), cache, caches);
    }

    @Bean
    RequireActiveFeed requireActiveFeed(ActiveFeedReader feeds) {
        return new RequireActiveFeed(feeds);
    }

    /** Keeps Prometheus scrapes and health probes out of the traces (DOC-28 §5.1). */
    @Bean
    ObservationPredicate skipActuator() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext request) {
                HttpServletRequest carrier = request.getCarrier();
                String uri = carrier == null ? null : carrier.getRequestURI();
                return uri == null || !uri.startsWith("/actuator");
            }
            return true;
        };
    }
}
