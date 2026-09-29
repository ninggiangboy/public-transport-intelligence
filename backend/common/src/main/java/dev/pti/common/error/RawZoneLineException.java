package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;
import org.jspecify.annotations.Nullable;

/** A raw-zone line that is not a readable record: bad JSON or a value that is not base64 (DOC-22 §4.4). */
public class RawZoneLineException extends DataException {

    private static final long serialVersionUID = 1L;

    private final String line;

    public RawZoneLineException(String message, String line, @Nullable Throwable cause) {
        super(DlqStage.DESERIALIZE, null, message, cause);
        this.line = line;
    }

    /** The whole line as read, dead-lettered after PII scrubbing. */
    public String line() {
        return line;
    }
}
