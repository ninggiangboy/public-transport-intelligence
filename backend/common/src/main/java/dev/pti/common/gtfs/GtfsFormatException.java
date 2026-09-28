package dev.pti.common.gtfs;

import dev.pti.common.error.FatalException;

/**
 * A GTFS file that cannot be read: bad CSV, a missing file or column, or a value that does not parse. Fatal for
 * the simulator, which cannot run without its feed (DOC-25 §13).
 */
public class GtfsFormatException extends FatalException {

    private static final long serialVersionUID = 1L;

    public GtfsFormatException(String message) {
        super(message);
    }

    public GtfsFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
