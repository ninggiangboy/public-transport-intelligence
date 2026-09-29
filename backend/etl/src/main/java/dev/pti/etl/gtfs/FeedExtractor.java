package dev.pti.etl.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Extracts the known GTFS files at the root of the zip (DOC-21 §3.1 step 5) and refuses what could harm the pod:
 * entries escaping the target directory (zip slip), too many entries, too many bytes, or an entry that expands more
 * than {@link #MAX_RATIO} times (zip bomb). Every refusal is GV-01.
 */
public final class FeedExtractor {

    static final int MAX_RATIO = 100;

    /** Small entries may compress very well; the ratio only counts beyond this size. */
    static final long RATIO_FLOOR = 1024 * 1024;

    /** What was extracted and what was left out (GV-14). */
    public record Extracted(TreeSet<String> files, List<String> ignored) {}

    private final long maxBytes;
    private final int maxEntries;

    public FeedExtractor(long maxBytes, int maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    public Extracted extract(Path zipFile, Path target) {
        try {
            FeedWorkspace.deleteTree(target);
            Files.createDirectories(target);
            Path base = target.toRealPath();
            TreeSet<String> files = new TreeSet<>();
            List<String> ignored = new ArrayList<>();
            long total = 0;
            int count = 0;
            try (ZipFile zip = new ZipFile(zipFile.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > maxEntries) {
                        throw new FeedRejectedException(
                                FeedCheck.GV_01, "The zip has more than " + maxEntries + " entries");
                    }
                    String name = entry.getName();
                    Path resolved = base.resolve(name).normalize();
                    if (!resolved.startsWith(base)) {
                        throw new FeedRejectedException(
                                FeedCheck.GV_01, "Zip entry " + name + " points outside the feed directory");
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (name.contains("/")
                            || name.contains("\\")
                            || GtfsTable.byFile(name).isEmpty()) {
                        ignored.add(name);
                        continue;
                    }
                    total += copy(zip, entry, resolved, maxBytes - total);
                    files.add(name);
                }
            } catch (ZipException e) {
                throw new FeedRejectedException(FeedCheck.GV_01, "Not a valid zip file: " + e.getMessage());
            }
            return new Extracted(files, List.copyOf(ignored));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static long copy(ZipFile zip, ZipEntry entry, Path target, long budget) throws IOException {
        long compressed = entry.getCompressedSize();
        long written = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = zip.getInputStream(entry);
                OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                written += n;
                if (written > budget) {
                    throw new FeedRejectedException(
                            FeedCheck.GV_01, "The feed expands to more than the allowed size at " + entry.getName());
                }
                if (compressed > 0 && written > RATIO_FLOOR && written > compressed * MAX_RATIO) {
                    throw new FeedRejectedException(
                            FeedCheck.GV_01,
                            "Zip entry " + entry.getName() + " expands more than " + MAX_RATIO + " times");
                }
                out.write(buffer, 0, n);
            }
        }
        return written;
    }
}
