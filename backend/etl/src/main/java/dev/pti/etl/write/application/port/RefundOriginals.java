package dev.pti.etl.write.application.port;

import dev.pti.etl.write.domain.OriginalSale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Looks up the originals a chunk's refunds point at, in the chunk transaction (DOC-16 §2.3). */
public interface RefundOriginals {

    /** The originals that exist in the warehouse among the given transaction ids, keyed by transaction id. */
    Map<UUID, OriginalSale> find(Set<UUID> transactionIds);
}
