package dev.pti.api.platform.adapter.in.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.adapter.in.web.CallerFactory;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.Role;
import dev.pti.api.testing.JwtFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** The token checks of DOC-27 §3.2, for the decoder of the Keycloak issuer, and the reading of its claims. */
class TokenHandlingTest {

    private static final String KEYCLOAK_ISSUER = "http://localhost:8180/realms/pti";
    private static final Duration SKEW = Duration.ofSeconds(30);

    private final JwtDecoder decoder =
            JwtValidation.fromPublicKey(JwtFixture.publicKey(), KEYCLOAK_ISSUER, JwtFixture.AUDIENCE, SKEW);

    private static String token() {
        return JwtFixture.token()
                .issuer(KEYCLOAK_ISSUER)
                .roles("operator", "viewer")
                .name("Demo Operator")
                .username("operator")
                .build();
    }

    @Test
    @DisplayName("A token of the Keycloak issuer, with the audience, signed RS256, is accepted")
    void acceptsAValidToken() {
        Jwt jwt = decoder.decode(token());

        assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("operator");
        assertThat(jwt.getAudience()).contains("pti-api");
    }

    @Test
    @DisplayName("SEC-02 expired, other key, other issuer, no audience, alg=none and HS256 are all refused")
    void refusesEveryBadToken() {
        String[] bad = {
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).expired().build(),
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).signedByAnotherKey().build(),
            JwtFixture.token().issuer("http://evil.example/realms/pti").build(),
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).audience().build(),
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).audience("other").build(),
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).unsigned().build(),
            JwtFixture.token().issuer(KEYCLOAK_ISSUER).hs256().build()
        };
        for (String token : bad) {
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void clockSkewOf30SecondsIsTolerated() {
        String justExpired = JwtFixture.token()
                .issuer(KEYCLOAK_ISSUER)
                .expiresAt(java.time.Instant.now().minusSeconds(10))
                .build();
        String longExpired = JwtFixture.token()
                .issuer(KEYCLOAK_ISSUER)
                .expiresAt(java.time.Instant.now().minusSeconds(90))
                .build();

        assertThat(decoder.decode(justExpired)).isNotNull();
        assertThatThrownBy(() -> decoder.decode(longExpired)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("Only the realm roles viewer and operator count; the principal is preferred_username")
    void converterMapsRealmRolesOnly() {
        Jwt jwt = decoder.decode(JwtFixture.token()
                .issuer(KEYCLOAK_ISSUER)
                .username("jane")
                .roles("offline_access", "default-roles-pti", "viewer")
                .build());

        var authentication = new KeycloakJwtConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("jane");
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_VIEWER");
    }

    @Test
    void converterFallsBackToTheSubject() {
        Jwt jwt =
                Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1").build();

        assertThat(new KeycloakJwtConverter().convert(jwt).getName()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("The caller has the roles after the hierarchy, its display name and its expiry")
    void callerFactoryAppliesTheHierarchy() {
        RoleHierarchy hierarchy = RoleHierarchyImpl.withDefaultRolePrefix()
                .role("OPERATOR")
                .implies("VIEWER")
                .build();
        Jwt jwt = decoder.decode(JwtFixture.token()
                .issuer(KEYCLOAK_ISSUER)
                .username("operator")
                .name("Demo Operator")
                .roles("operator")
                .build());

        Caller caller = new CallerFactory(hierarchy).from(new KeycloakJwtConverter().convert(jwt));

        assertThat(caller.username()).isEqualTo("operator");
        assertThat(caller.displayName()).isEqualTo("Demo Operator");
        assertThat(caller.roles()).isEqualTo(Set.of(Role.OPERATOR, Role.VIEWER));
        assertThat(caller.tokenExpiresAt()).isEqualTo(jwt.getExpiresAt());
        assertThat(new CallerFactory(hierarchy).from(null)).isEqualTo(Caller.anonymous());
    }

    @Test
    void publicKeyFileIsReadFromPem(@TempDir Path dir) throws IOException {
        Path pem = JwtFixture.writePublicKey(dir.resolve("key.pem"));

        assertThat(Files.readString(pem)).startsWith("-----BEGIN PUBLIC KEY-----");
        assertThat(PublicKeys.readRsaPem(pem)).isEqualTo(JwtFixture.publicKey());
    }

    @Test
    void anUnreadableKeyIsFatal(@TempDir Path dir) throws IOException {
        Path bad = Files.writeString(dir.resolve("bad.pem"), "not a key");

        assertThatThrownBy(() -> PublicKeys.readRsaPem(bad)).isInstanceOf(dev.pti.common.error.FatalException.class);
        assertThatThrownBy(() -> PublicKeys.readRsaPem(dir.resolve("missing.pem")))
                .isInstanceOf(dev.pti.common.error.FatalException.class);
    }
}
