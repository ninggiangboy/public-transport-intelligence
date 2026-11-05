package dev.pti.etl.gtfs.application.port;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

/** Where a feed may come from and how it is copied into the workspace (DOC-21 §1.1, §3.1). */
public interface FeedDownload {

    /** Raw zone prefix the static feeds live under (DOC-18 §2). */
    String RAW_PREFIX = "gtfs-static/";

    /**
     * @return the URI, when it is allowed
     * @throws IllegalArgumentException with the reason otherwise
     */
    URI check(String sourceUri);

    /** The raw zone key of an {@code s3:} source, when it is one (such a feed is not uploaded again). */
    Optional<String> rawKeyOf(URI uri);

    /** Copies the feed to {@code target} and returns its SHA-256 in lower-case hex. */
    String copy(URI uri, Path target);
}
