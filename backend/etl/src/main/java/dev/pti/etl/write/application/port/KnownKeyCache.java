package dev.pti.etl.write.application.port;

import java.util.Collection;
import java.util.List;

/**
 * Dimension keys known to be committed (DOC-20 §4.5), so that placeholder inserts for {@code dim_vehicle} and
 * {@code dim_sale_point} are not repeated every chunk. Keys are added only after commit; losing the cache on restart
 * costs a few {@code DO NOTHING} inserts.
 */
public interface KnownKeyCache {

    List<String> unknownVehicles(Collection<String> ids);

    List<String> unknownSalePoints(Collection<String> ids);

    void addVehiclesAfterCommit(Collection<String> ids);

    void addSalePointsAfterCommit(Collection<String> ids);
}
