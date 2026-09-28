package dev.pti.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The mini GTFS feed of DOC-44 §5.1, cut from the real feed by {@code sample-data/gtfs/make_mini_feed.py}. It is
 * kept as plain files so that diffs stay readable, and zipped on demand for code that reads a feed archive.
 */
public final class GtfsFixtures {

    public static final String MINI_FEED = "gtfs/mini/";

    /** Every file of the mini feed, in the order they are written to the zip. */
    public static final List<String> MINI_FEED_FILES = List.of(
            "agency.txt",
            "feed_info.txt",
            "routes.txt",
            "trips.txt",
            "stop_times.txt",
            "stops.txt",
            "calendar.txt",
            "calendar_dates.txt",
            "shapes.txt",
            "vehicles.txt");

    private GtfsFixtures() {}

    /** Opens one file of the mini feed. */
    public static InputStream open(String name) {
        InputStream in = GtfsFixtures.class.getClassLoader().getResourceAsStream(MINI_FEED + name);
        if (in == null) {
            throw new IllegalArgumentException("No such file in the mini feed: " + name);
        }
        return in;
    }

    /** Writes the mini feed as a GTFS zip to {@code target} and returns it. */
    public static Path miniFeedZip(Path target) {
        try (OutputStream out = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : MINI_FEED_FILES) {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream in = open(name)) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }
}
