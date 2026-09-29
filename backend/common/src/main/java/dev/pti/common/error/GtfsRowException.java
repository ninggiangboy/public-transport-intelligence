package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;

/**
 * A GTFS static row that does not parse (DOC-21 §3.2). It is collected into the feed's validation report, never
 * dead-lettered, because a feed is accepted or rejected as a whole.
 */
public class GtfsRowException extends DataException {

    private static final long serialVersionUID = 1L;

    private final String file;
    private final long line;

    public GtfsRowException(String file, long line, String message) {
        super(DlqStage.QUALITY, null, message, null);
        this.file = file;
        this.line = line;
    }

    public String file() {
        return file;
    }

    public long line() {
        return line;
    }
}
