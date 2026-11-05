package dev.pti.etl.gtfs.domain;

import dev.pti.common.error.FatalException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The SHA-256 of a feed file, in lower-case hex; it identifies a feed version (DOC-21 §3.1). */
public final class FeedDigest {

    private FeedDigest() {}

    public static String sha256(Path file) {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new FatalException("Cannot read " + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new FatalException("SHA-256 is not available", e);
        }
    }
}
