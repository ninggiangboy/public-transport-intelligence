package dev.pti.analytics.recompute.domain;

/**
 * What a recompute did for one detector (DOC-23 §11.7): the units it ran, the rows it wrote and the rows it removed.
 * Items of the same detector add up.
 *
 * @param scopes how many work items ran
 * @param upserted rows inserted or updated
 * @param deleted rows deleted because the recompute did not reproduce them
 */
public record DetectorStats(int scopes, int upserted, int deleted) {

    public static final DetectorStats NONE = new DetectorStats(0, 0, 0);

    public DetectorStats plus(DetectorStats other) {
        return new DetectorStats(scopes + other.scopes, upserted + other.upserted, deleted + other.deleted);
    }
}
