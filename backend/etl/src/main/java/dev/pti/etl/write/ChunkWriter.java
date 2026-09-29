package dev.pti.etl.write;

import dev.pti.etl.core.WriteSet;
import java.util.List;

/**
 * Writes the processed items of one chunk inside the caller's transaction (DOC-19 §4.3). Shared by streaming and
 * batch mode; {@link FactChunkWriter} is the real one, {@code BaselineFactWriter} the experiment one (DR-27).
 */
public interface ChunkWriter {

    WriteOutcome write(List<WriteSet> items, WriteContext context);
}
