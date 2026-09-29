package dev.pti.common.error;

import dev.pti.common.dq.DlqStage;
import org.jspecify.annotations.Nullable;

/** The bytes are not a readable message: bad UTF-8, broken JSON, or an envelope without its type fields. */
public class DeserializationException extends DataException {

    private static final long serialVersionUID = 1L;

    public DeserializationException(String message, @Nullable Throwable cause) {
        super(DlqStage.DESERIALIZE, null, message, cause);
    }
}
