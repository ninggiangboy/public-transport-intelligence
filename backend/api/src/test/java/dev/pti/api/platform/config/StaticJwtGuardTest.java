package dev.pti.api.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.error.FatalException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** SEC-13 and DOC-27 §3.3: the static key never runs next to Keycloak, the demo profile or other profiles. */
class StaticJwtGuardTest {

    private static StaticJwtGuard guard(MockEnvironment environment) {
        return new StaticJwtGuard(environment);
    }

    @Test
    void staticJwtAloneIsAllowed() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("static-jwt");

        assertThatCode(() -> guard(environment).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void k8sIsTheOnlyCompanion() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("static-jwt", "k8s");

        assertThatCode(() -> guard(environment).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-13 PTI_KEYCLOAK_URL together with static-jwt stops the application")
    void keycloakUrlIsRefused() {
        MockEnvironment environment = new MockEnvironment().withProperty("PTI_KEYCLOAK_URL", "http://localhost:8180");
        environment.setActiveProfiles("static-jwt");

        assertThatThrownBy(() -> guard(environment).afterPropertiesSet())
                .isInstanceOf(FatalException.class)
                .hasMessageContaining("PTI_KEYCLOAK_URL");
    }

    @Test
    void anEmptyKeycloakUrlIsNotSet() {
        MockEnvironment environment = new MockEnvironment().withProperty("PTI_KEYCLOAK_URL", " ");
        environment.setActiveProfiles("static-jwt");

        assertThatCode(() -> guard(environment).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void demoAndOtherProfilesAreRefused() {
        for (String other : new String[] {"demo", "dev", "stream"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("static-jwt", other);

            assertThatThrownBy(() -> guard(environment).afterPropertiesSet())
                    .isInstanceOf(FatalException.class)
                    .hasMessageContaining(other);
        }
    }
}
