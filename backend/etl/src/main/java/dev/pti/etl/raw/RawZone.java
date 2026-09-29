package dev.pti.etl.raw;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The raw zone bucket (ADR-0012, DOC-18 §2). {@code etl} may read every prefix and write {@code gtfs-static/} only
 * (DOC-18 §3). Keys are relative to the bucket and include {@code pti.s3.raw-prefix}.
 */
public interface RawZone {

    String bucket();

    /** The key of an object under the configured raw prefix, e.g. {@code gtfs-static/<hash>.zip}. */
    String key(String relative);

    boolean exists(String key);

    void put(String key, Path file);

    /** @throws RawObjectMissingException when there is no such object */
    void download(String key, Path target);

    /** Every key under {@code prefix}, in no particular order. */
    List<String> list(String prefix);

    /**
     * Streams an object; the caller closes the stream.
     *
     * @throws RawObjectMissingException when there is no such object
     */
    InputStream open(String key);

    /** A missing object is not transient: retrying will not make it appear. */
    final class RawObjectMissingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public RawObjectMissingException(String key) {
            super("No raw zone object " + key);
        }
    }
}
