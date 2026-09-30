package dev.pti.api.testing;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Self-signed JWTs for the tests of {@code api} (DOC-44 §5.3): tokens shaped like Keycloak's access tokens of realm
 * {@code pti} ({@code realm_access.roles}, {@code preferred_username}, {@code name}, {@code aud}), signed RS256 with
 * a key pair that exists only in the test JVM. A token that should be refused is built by changing one thing: expired,
 * signed by another key, wrong issuer, no audience, {@code alg=none} or {@code HS256}.
 *
 * <p>The default issuer is {@value #STATIC_ISSUER}, the one of the {@code static-jwt} profile, whose decoder the tests
 * use with {@link #writePublicKey(Path)}.
 */
public final class JwtFixture {

    /** Issuer of the {@code static-jwt} profile (DOC-27 §3.3). */
    public static final String STATIC_ISSUER = "pti-static";

    /** Audience of the API (DOC-27 §3.2). */
    public static final String AUDIENCE = "pti-api";

    private static final KeyPair KEYS = newKeyPair();

    private JwtFixture() {}

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAPublicKey publicKey() {
        return (RSAPublicKey) KEYS.getPublic();
    }

    /** Writes the public key as PEM, which {@code pti.api.security.static-jwt.public-key-file} points to. */
    public static Path writePublicKey(Path file) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(publicKey().getEncoded());
        try {
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    public static Token token() {
        return new Token();
    }

    /** A valid token of the demo user {@code viewer}. */
    public static String viewer() {
        return token().username("viewer").name("Demo Viewer").roles("viewer").build();
    }

    /** A valid token of the demo user {@code operator}, who carries both roles as Keycloak's composite role does. */
    public static String operator() {
        return token().username("operator")
                .name("Demo Operator")
                .roles("operator", "viewer")
                .build();
    }

    /** The {@code Authorization} header value of a token. */
    public static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Builds one token; every setter changes one thing of an otherwise valid viewer token. */
    public static final class Token {

        private String username = "viewer";
        private String name = "Demo Viewer";
        private List<String> roles = List.of("viewer");
        private String issuer = STATIC_ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private Instant expiresAt = Instant.now().plus(Duration.ofMinutes(5));
        private KeyPair signingKeys = KEYS;
        private Mode mode = Mode.RS256;

        private enum Mode {
            RS256,
            HS256,
            NONE
        }

        public Token username(String value) {
            this.username = value;
            return this;
        }

        public Token name(String value) {
            this.name = value;
            return this;
        }

        public Token roles(String... values) {
            this.roles = List.of(values);
            return this;
        }

        public Token issuer(String value) {
            this.issuer = value;
            return this;
        }

        /** No argument means no {@code aud} claim. */
        public Token audience(String... values) {
            this.audience = List.of(values);
            return this;
        }

        public Token expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        public Token expired() {
            return expiresAt(Instant.now().minus(Duration.ofHours(1)));
        }

        /** Signed by a key that the API does not trust. */
        public Token signedByAnotherKey() {
            this.signingKeys = newKeyPair();
            return this;
        }

        /** {@code alg=HS256}, the algorithm confusion attack: an HMAC with some secret. */
        public Token hs256() {
            this.mode = Mode.HS256;
            return this;
        }

        /** {@code alg=none}: no signature at all. */
        public Token unsigned() {
            this.mode = Mode.NONE;
            return this;
        }

        /** Now, or an hour before an expiry that is already past, so that the token is expired and nothing else. */
        private Instant issuedAt() {
            Instant now = Instant.now();
            return expiresAt.isAfter(now) ? now : expiresAt.minus(Duration.ofHours(1));
        }

        public String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(UUID.nameUUIDFromBytes(username.getBytes(StandardCharsets.UTF_8))
                            .toString())
                    .issuer(issuer)
                    .issueTime(Date.from(issuedAt()))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("preferred_username", username)
                    .claim("name", name)
                    .claim("realm_access", Map.of("roles", roles));
            if (!audience.isEmpty()) {
                claims.audience(audience);
            }
            JWTClaimsSet set = claims.build();
            try {
                return switch (mode) {
                    case NONE -> new PlainJWT(set).serialize();
                    case HS256 -> {
                        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), set);
                        jwt.sign(new MACSigner(
                                "a-very-long-secret-of-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8)));
                        yield jwt.serialize();
                    }
                    case RS256 -> {
                        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), set);
                        jwt.sign(new RSASSASigner((RSAPrivateKey) signingKeys.getPrivate()));
                        yield jwt.serialize();
                    }
                };
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
