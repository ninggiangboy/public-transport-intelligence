package dev.pti.etl.gtfs;

import dev.pti.common.error.FatalException;
import dev.pti.common.error.TransientInfraException;
import dev.pti.etl.raw.RawZone;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a feed may come from (DOC-21 §1.1) and how it is copied into the workspace. {@link #check} is also what the
 * job request poller uses to reject a bad {@code sourceUri} before anything runs (test G-13).
 */
public class FeedSource {

    static final String RAW_PREFIX = "gtfs-static/";
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
    static final long MAX_HTTP_BYTES = 200L * 1024 * 1024;

    private final List<Path> allowedDirs;
    private final boolean allowHttp;
    private final RawZone raw;

    public FeedSource(List<Path> allowedDirs, boolean allowHttp, RawZone raw) {
        this.allowedDirs =
                allowedDirs.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        this.allowHttp = allowHttp;
        this.raw = raw;
    }

    /**
     * @return the URI, when it is allowed
     * @throws IllegalArgumentException with the reason otherwise
     */
    public URI check(String sourceUri) {
        URI uri;
        try {
            uri = new URI(sourceUri.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("sourceUri is not a URI: " + sourceUri);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "file" -> {
                Path path = Path.of(uri).toAbsolutePath().normalize();
                if (allowedDirs.stream().noneMatch(path::startsWith)) {
                    throw new IllegalArgumentException(
                            "sourceUri " + sourceUri + " is outside the allowed directories");
                }
            }
            case "s3" -> rawKey(uri);
            case "https" -> {
                if (!allowHttp) {
                    throw new IllegalArgumentException("https sources are disabled (pti.gtfs.static.allow-http)");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported sourceUri scheme: " + sourceUri);
        }
        return uri;
    }

    /** The raw zone key of an {@code s3:} source, when it is one (such a feed is not uploaded again). */
    public Optional<String> rawKeyOf(URI uri) {
        return "s3".equalsIgnoreCase(uri.getScheme()) ? Optional.of(rawKey(uri)) : Optional.empty();
    }

    private String rawKey(URI uri) {
        String key = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!raw.bucket().equals(uri.getHost()) || !key.startsWith(raw.key(RAW_PREFIX)) || key.contains("..")) {
            throw new IllegalArgumentException(
                    "s3 sources must be in s3://" + raw.bucket() + "/" + raw.key(RAW_PREFIX) + ": " + uri);
        }
        return key;
    }

    /** Copies the feed to {@code target} and returns its SHA-256 in lower-case hex. */
    public String copy(URI uri, Path target) {
        try {
            Files.createDirectories(
                    java.util.Objects.requireNonNull(target.toAbsolutePath().getParent()));
            Optional<String> key = rawKeyOf(uri);
            if (key.isPresent()) {
                raw.download(key.get(), target);
                return sha256(target);
            }
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                try (InputStream in = Files.newInputStream(Path.of(uri))) {
                    return copy(in, target, Long.MAX_VALUE);
                }
            }
            return download(uri, target);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new FatalException("Feed file not found: " + uri, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String download(URI uri, Path target) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder(uri).timeout(HTTP_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() >= 500) {
                    throw new TransientInfraException("Feed download failed with HTTP " + response.statusCode(), null);
                }
                if (response.statusCode() != 200) {
                    throw new FatalException("Feed download failed with HTTP " + response.statusCode());
                }
                return copy(in, target, MAX_HTTP_BYTES);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientInfraException("Feed download interrupted", e);
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
            throw new TransientInfraException("Feed download failed: " + e.getMessage(), e);
        }
    }

    private static String copy(InputStream in, Path target, long maxBytes) throws IOException {
        MessageDigest digest = digest();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream digesting = new DigestInputStream(in, digest);
                OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = digesting.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new FatalException("Feed is larger than " + maxBytes + " bytes");
                }
                out.write(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(Path file) {
        MessageDigest digest = digest();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
