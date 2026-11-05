package dev.pti.etl.gtfs.application.port;

import dev.pti.etl.gtfs.domain.GtfsTable;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The local files of one job instance (DOC-21 §3.1): {@code <work-dir>/<jobInstanceId>/feed.zip} and the extracted
 * {@code feed/}. Keyed by instance, not execution, so a restart on the same pod finds them.
 */
public interface FeedFiles {

    Path dir(long jobInstanceId);

    Path zip(long jobInstanceId);

    Path files(long jobInstanceId);

    Path file(long jobInstanceId, GtfsTable table);

    void delete(long jobInstanceId);

    /** Removes the workspaces of failed jobs nobody restarted (DOC-21 §5). */
    void deleteOlderThan(Duration age);
}
