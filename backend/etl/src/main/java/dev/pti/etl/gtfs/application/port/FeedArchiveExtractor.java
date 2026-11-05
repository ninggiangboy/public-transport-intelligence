package dev.pti.etl.gtfs.application.port;

import dev.pti.etl.gtfs.application.ExtractedFeed;
import java.nio.file.Path;

/**
 * Extracts the known GTFS files at the root of a feed zip and refuses anything that could harm the pod: entries
 * escaping the target directory (zip slip), too many entries, too many bytes or a zip bomb (DOC-21 §3.1 step 5).
 */
public interface FeedArchiveExtractor {

    ExtractedFeed extract(Path zipFile, Path target);
}
