package dev.pti.simulator.feed;

import dev.pti.testing.GtfsFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** The mini feed and the real feed, each loaded once per JVM. */
public final class Feeds {

    public static final String REAL_SHA256 = "2cfc4a1b6efd7e860cbc0ee4bc90487fb8a5eb3f7946b35df021569d032ee23b";

    private Feeds() {}

    public static Feed mini() {
        return Mini.FEED;
    }

    /** The pinned Metro Transit feed from {@code sample-data/gtfs/} (committed, so always present). */
    public static Feed real() {
        return Real.FEED;
    }

    public static Path realPath() {
        return Path.of(System.getProperty("pti.repo-root"), "sample-data/gtfs/metrotransit-mn-20260926.zip");
    }

    public static Path miniZip() {
        try {
            Path dir = Files.createTempDirectory("mini-feed");
            dir.toFile().deleteOnExit();
            Path zip = GtfsFixtures.miniFeedZip(dir.resolve("mini.zip"));
            zip.toFile().deleteOnExit();
            return zip;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Mini {
        static final Feed FEED = FeedLoader.load(miniZip(), null);
    }

    private static final class Real {
        static final Feed FEED = FeedLoader.load(realPath(), REAL_SHA256);
    }
}
