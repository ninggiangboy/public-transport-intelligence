package dev.pti.api.etlops.domain;

import dev.pti.api.platform.domain.ValidationException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The {@code Idempotency-Key} header (DOC-31 §7.1, §8): 1 to 100 characters of {@code [A-Za-z0-9_-]}. */
public final class IdempotencyKey {

    public static final String HEADER = "Idempotency-Key";

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private IdempotencyKey() {}

    /**
     * @return the key, or {@code null} when the header is absent
     * @throws ValidationException on the field {@code Idempotency-Key} when it is present and not valid
     */
    public static @Nullable String check(@Nullable String value) {
        if (value == null) {
            return null;
        }
        if (!VALID.matcher(value).matches()) {
            throw ValidationException.of(HEADER, "must be 1 to 100 characters of letters, digits, '_' and '-'");
        }
        return value;
    }
}
