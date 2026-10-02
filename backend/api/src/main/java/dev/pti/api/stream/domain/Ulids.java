package dev.pti.api.stream.domain;

import java.time.Instant;
import java.util.Optional;

/** The time part of a ULID: its first 10 characters, Crockford base32, milliseconds since the epoch. */
public final class Ulids {

    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private Ulids() {}

    /** The instant of a ULID, or empty when the text is not one (wrong length or characters). */
    public static Optional<Instant> time(String text) {
        if (text.length() != 26) {
            return Optional.empty();
        }
        long millis = 0;
        for (int i = 0; i < 10; i++) {
            int digit = ALPHABET.indexOf(Character.toUpperCase(text.charAt(i)));
            if (digit < 0) {
                return Optional.empty();
            }
            millis = millis * 32 + digit;
        }
        for (int i = 10; i < 26; i++) {
            if (ALPHABET.indexOf(Character.toUpperCase(text.charAt(i))) < 0) {
                return Optional.empty();
            }
        }
        // 10 characters hold 50 bits; a ULID's time is 48 bits, so the first character is at most 7.
        if (millis >= 1L << 48) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(millis));
    }
}
