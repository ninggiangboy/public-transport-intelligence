package dev.pti.api.etlops.application.port;

import dev.pti.api.etlops.domain.FeedVersion;
import java.util.List;

/** Reads the GTFS feed versions (DOC-32 E-38), as {@code api_reader}. */
public interface FeedVersionReader {

    /** The newest versions by {@code loaded_at}, newest first. */
    List<FeedVersion> latest(int limit);
}
