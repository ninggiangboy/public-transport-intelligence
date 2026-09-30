package dev.pti.api.platform.config;

import dev.pti.common.error.FatalException;
import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Keeps the static key of the {@code lite} mode out of any environment that has Keycloak (DOC-27 §3.3): the
 * {@code static-jwt} profile refuses to start together with {@code PTI_KEYCLOAK_URL}, with the {@code demo} profile or
 * with any profile other than {@code k8s}.
 */
public final class StaticJwtGuard implements InitializingBean {

    static final String PROFILE = "static-jwt";
    private static final Set<String> ALLOWED_COMPANIONS = Set.of(PROFILE, "k8s");

    private final Environment environment;

    public StaticJwtGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String keycloakUrl = environment.getProperty("PTI_KEYCLOAK_URL");
        if (keycloakUrl != null && !keycloakUrl.isBlank()) {
            throw new FatalException("Profile static-jwt cannot run together with PTI_KEYCLOAK_URL");
        }
        for (String profile : Arrays.asList(environment.getActiveProfiles())) {
            if (!ALLOWED_COMPANIONS.contains(profile)) {
                throw new FatalException(
                        "Profile static-jwt cannot run together with profile " + profile + "; only k8s is allowed");
            }
        }
    }
}
