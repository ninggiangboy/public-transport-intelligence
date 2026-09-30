package dev.pti.api.platform.adapter.in.security;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The checks every token passes (DOC-27 §3.2), the same for Keycloak and for the static key of the {@code lite} mode:
 * RS256 signature only (so {@code HS256} and {@code alg=none} are refused), {@code iss} equal to the configured
 * issuer, {@code aud} containing the API's audience, {@code exp} and {@code nbf} with a clock skew.
 */
public final class JwtValidation {

    private JwtValidation() {}

    /** A decoder that fetches the signing keys from the JWKS endpoint (lazily: Keycloak may start after the API). */
    public static JwtDecoder fromJwkSetUri(String jwkSetUri, String issuer, String audience, Duration clockSkew) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(issuer, audience, clockSkew));
        return decoder;
    }

    /** A decoder for one public key, for the {@code static-jwt} profile and for tests. */
    public static JwtDecoder fromPublicKey(RSAPublicKey key, String issuer, String audience, Duration clockSkew) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(issuer, audience, clockSkew));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(String issuer, String audience, Duration clockSkew) {
        JwtClaimValidator<List<String>> audiences =
                new JwtClaimValidator<>("aud", aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(clockSkew), new JwtIssuerValidator(issuer), audiences);
    }
}
