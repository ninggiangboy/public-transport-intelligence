package dev.pti.etl.gtfs;

import dev.pti.common.dq.DlqStage;
import dev.pti.common.error.DataException;

/** One GTFS row that cannot be loaded (DOC-21 §3.2). It is a row error of the feed (GV-04), not a dead letter. */
public class GtfsRowException extends DataException {

    private static final long serialVersionUID = 1L;

    public GtfsRowException(String message) {
        super(DlqStage.SCHEMA, FeedCheck.GV_04.code(), message, null);
    }
}
