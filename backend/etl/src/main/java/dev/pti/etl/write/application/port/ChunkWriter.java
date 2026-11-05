package dev.pti.etl.write.application.port;

import dev.pti.etl.core.domain.WriteSet;
import dev.pti.etl.write.domain.WriteContext;
import dev.pti.etl.write.domain.WriteOutcome;
import java.util.List;

/**
 * Writes the processed items of one chunk inside the caller's transaction (DOC-19 §4.3). Shared by streaming and
 * batch mode; {@link FactChunkWriter} is the real one, {@code BaselineFactWriter} the experiment one (DR-27).
 */
public interface ChunkWriter {

    WriteOutcome write(List<WriteSet> items, WriteContext context);
}
