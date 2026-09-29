package dev.pti.etl.gtfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local files of one job instance: {@code <work-dir>/<jobInstanceId>/feed.zip} and the extracted
 * {@code feed/} (DOC-21 §3.1). Keyed by instance, not execution, so that a restart on the same pod finds them;
 * on another pod {@link FeedWorkspaceListener} downloads the zip again from the raw zone.
 */
public class FeedWorkspace {

    private static final Logger log = LoggerFactory.getLogger(FeedWorkspace.class);

    private final Path root;

    public FeedWorkspace(Path root) {
        this.root = root;
    }

    public Path dir(long jobInstanceId) {
        return root.resolve(Long.toString(jobInstanceId));
    }

    public Path zip(long jobInstanceId) {
        return dir(jobInstanceId).resolve("feed.zip");
    }

    public Path files(long jobInstanceId) {
        return dir(jobInstanceId).resolve("feed");
    }

    public Path file(long jobInstanceId, GtfsTable table) {
        return files(jobInstanceId).resolve(table.file());
    }

    public void delete(long jobInstanceId) {
        deleteTree(dir(jobInstanceId));
    }

    /** Leftovers of failed jobs nobody restarted (DOC-21 §5). */
    public void deleteOlderThan(Duration age) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(age);
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .filter(d -> modifiedBefore(d, cutoff))
                    .forEach(FeedWorkspace::deleteTree);
        } catch (IOException e) {
            log.warn("Cannot clean the GTFS work directory {}: {}", root, e.toString());
        }
    }

    private static boolean modifiedBefore(Path dir, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(dir).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Cannot delete {}: {}", dir, e.toString());
        }
    }
}
