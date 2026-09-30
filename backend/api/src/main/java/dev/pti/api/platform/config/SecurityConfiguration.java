package dev.pti.api.platform.config;

import dev.pti.api.platform.adapter.in.ratelimit.RateLimitFilter;
import dev.pti.api.platform.adapter.in.ratelimit.RateLimiter;
import dev.pti.api.platform.adapter.in.security.CallerContextFilter;
import dev.pti.api.platform.adapter.in.security.EndpointAuthorizationManager;
import dev.pti.api.platform.adapter.in.security.EndpointRules;
import dev.pti.api.platform.adapter.in.security.JwtValidation;
import dev.pti.api.platform.adapter.in.security.KeycloakJwtConverter;
import dev.pti.api.platform.adapter.in.security.ProblemAccessDeniedHandler;
import dev.pti.api.platform.adapter.in.security.ProblemAuthenticationEntryPoint;
import dev.pti.api.platform.adapter.in.security.PublicKeys;
import dev.pti.api.platform.adapter.in.security.WebhookToken;
import dev.pti.api.platform.adapter.in.security.WebhookTokenFilter;
import dev.pti.api.platform.adapter.in.web.ProblemWriter;
import dev.pti.common.error.FatalException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Security (DOC-27): an OAuth2 resource server that checks Keycloak tokens, stateless and without CSRF (bearer tokens
 * only, no cookies, §5.1) and without CORS (the browser always calls the same origin, §5.2). Four chains: {@code
 * /api/**} with the endpoint matrix, {@code /internal/**} with the webhook token, the OpenAPI documents when they are
 * enabled, and a last one that denies everything else, so a forgotten endpoint is closed rather than open.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfiguration {

    /** The issuer of tokens minted for the {@code lite} mode (DOC-27 §3.3). */
    static final String STATIC_ISSUER = "pti-static";

    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("OPERATOR")
                .implies("VIEWER")
                .build();
    }

    @Bean
    @Profile("!" + StaticJwtGuard.PROFILE)
    JwtDecoder keycloakJwtDecoder(
            ApiProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri) {
        ApiProperties.Security security = properties.security();
        return JwtValidation.fromJwkSetUri(jwkSetUri, security.issuer(), security.audience(), security.clockSkew());
    }

    @Bean
    @Profile(StaticJwtGuard.PROFILE)
    JwtDecoder staticJwtDecoder(ApiProperties properties) {
        ApiProperties.Security security = properties.security();
        ApiProperties.StaticJwt staticJwt = security.staticJwt();
        if (staticJwt == null || staticJwt.publicKeyFile() == null) {
            throw new FatalException("pti.api.security.static-jwt.public-key-file is required by profile static-jwt");
        }
        return JwtValidation.fromPublicKey(
                PublicKeys.readRsaPem(staticJwt.publicKeyFile()),
                STATIC_ISSUER,
                security.audience(),
                security.clockSkew());
    }

    @Bean
    @Profile(StaticJwtGuard.PROFILE)
    StaticJwtGuard staticJwtGuard(Environment environment) {
        return new StaticJwtGuard(environment);
    }

    @Bean
    ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(ProblemWriter problems) {
        return new ProblemAuthenticationEntryPoint(problems);
    }

    @Bean
    ProblemAccessDeniedHandler problemAccessDeniedHandler(ProblemWriter problems) {
        return new ProblemAccessDeniedHandler(problems);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    WebhookToken webhookToken(ApiProperties properties) {
        return new WebhookToken(properties.alertWebhook().tokenFile());
    }

    /**
     * The actuator endpoints (health, info, prometheus) on the management port (DOC-27 §6): open, because the port is
     * reachable only from inside the network and Prometheus and the container health checks send no token. The matcher
     * matches only on that port, so the application port stays closed to {@code /actuator/**}.
     */
    @Bean
    @Order(5)
    SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /** {@code /api/**}: the matrix of {@link EndpointRules}, tokens from Keycloak, problems instead of HTML. */
    @Bean
    @Order(10)
    SecurityFilterChain apiChain(
            HttpSecurity http,
            RoleHierarchy hierarchy,
            Environment environment,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings,
            JwtDecoder decoder,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler deniedHandler,
            ObjectProvider<RateLimiter> rateLimiter,
            ProblemWriter problems)
            throws Exception {
        boolean demo = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("demo"));
        EndpointAuthorizationManager matrix =
                new EndpointAuthorizationManager(EndpointRules.api(demo), hierarchy, handlerExists(handlerMappings));
        http.securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .access(matrix))
                .oauth2ResourceServer(oauth -> oauth.jwt(
                                jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(new KeycloakJwtConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .anonymous(Customizer.withDefaults())
                .addFilterAfter(new CallerContextFilter(), BearerTokenAuthenticationFilter.class);
        RateLimiter limiter = rateLimiter.getIfAvailable();
        if (limiter != null) {
            http.addFilterAfter(new RateLimitFilter(limiter, problems), AuthorizationFilter.class);
        }
        return http.build();
    }

    /** {@code /internal/**}: the Alertmanager webhook token, no OAuth2 (DOC-27 §6). */
    @Bean
    @Order(20)
    SecurityFilterChain internalChain(
            HttpSecurity http,
            RoleHierarchy hierarchy,
            WebhookToken webhookToken,
            ProblemWriter problems,
            ProblemAuthenticationEntryPoint entryPoint,
            ProblemAccessDeniedHandler deniedHandler)
            throws Exception {
        EndpointAuthorizationManager matrix =
                new EndpointAuthorizationManager(EndpointRules.internal(), hierarchy, request -> true);
        http.securityMatcher("/internal/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .access(matrix))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .addFilterBefore(new WebhookTokenFilter(webhookToken, problems), AuthorizationFilter.class);
        return http.build();
    }

    /** The OpenAPI document, which is only served when springdoc is on (profile {@code dev}, DOC-31 §12). */
    @Bean
    @Order(30)
    @ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
    SecurityFilterChain documentationChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /** Everything else is denied, for every method, so that an endpoint nobody declared cannot be reached. */
    @Bean
    @Order(100)
    SecurityFilterChain denyRestChain(
            HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint, ProblemAccessDeniedHandler deniedHandler)
            throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                        .permitAll()
                        .anyRequest()
                        .denyAll())
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler));
        return http.build();
    }

    /**
     * Whether Spring MVC has a handler for the request, asked from the security filter. The request path is parsed
     * for the lookup, as the DispatcherServlet does later, and left as found.
     */
    private static Predicate<HttpServletRequest> handlerExists(
            ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        return request -> {
            boolean parsedHere = !ServletRequestPathUtils.hasParsedRequestPath(request);
            if (parsedHere) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            try {
                for (RequestMappingHandlerMapping mapping :
                        handlerMappings.orderedStream().toList()) {
                    if (mapping.getHandler(request) != null) {
                        return true;
                    }
                }
                return false;
            } catch (Exception e) {
                // A handler exists but refuses this request (wrong method, media type): not a 404.
                return true;
            } finally {
                if (parsedHere) {
                    ServletRequestPathUtils.clearParsedRequestPath(request);
                }
            }
        };
    }
}
