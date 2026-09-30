package dev.pti.api.platform.adapter.in.security;

import dev.pti.common.error.FatalException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Reads the RSA public key of the {@code static-jwt} profile from a PEM file ({@code BEGIN PUBLIC KEY}). */
public final class PublicKeys {

    private PublicKeys() {}

    /** @throws FatalException when the file is missing or does not hold an RSA public key */
    public static RSAPublicKey readRsaPem(Path file) {
        try {
            String pem = Files.readString(file, StandardCharsets.UTF_8)
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new FatalException("Cannot read an RSA public key from " + file, e);
        }
    }
}
