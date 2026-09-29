package dev.pti.etl.core;

import dev.pti.common.error.DeserializationException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Decoding of message bytes (DOC-20 §4.1). */
public final class Utf8 {

    private Utf8() {}

    /** Strict decoding: bad UTF-8 is a data error, not a reason to guess. */
    public static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new DeserializationException("Invalid UTF-8", e);
        }
    }

    /**
     * Lenient decoding for storage in a {@code TEXT} column: bad sequences become U+FFFD, and so does U+0000,
     * which PostgreSQL does not accept.
     */
    public static String forStorage(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace('\u0000', '�');
    }
}
