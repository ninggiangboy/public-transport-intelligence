package dev.pti.common.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads the files of a GTFS zip (DOC-13 §2.4), shared by the simulator and {@code GtfsStaticLoadJob}. Entries are
 * looked up by name at the root of the archive.
 */
public final class GtfsZipReader implements AutoCloseable {

    private final ZipFile zip;

    public GtfsZipReader(Path path) {
        try {
            this.zip = new ZipFile(path.toFile());
        } catch (IOException e) {
            throw new GtfsFormatException("Cannot open GTFS zip " + path, e);
        }
    }

    public boolean has(String file) {
        return zip.getEntry(file) != null;
    }

    /**
     * Streams the rows of one file and returns its header.
     *
     * @throws GtfsFormatException when the file is missing or not valid CSV
     */
    public List<String> read(String file, Consumer<GtfsRecord> rows) {
        ZipEntry entry = zip.getEntry(file);
        if (entry == null) {
            throw new GtfsFormatException("Missing " + file);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return GtfsCsv.read(file, in, rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        try {
            zip.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
