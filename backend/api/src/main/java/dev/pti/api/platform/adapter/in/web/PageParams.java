package dev.pti.api.platform.adapter.in.web;

import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.ValidationException;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns {@code ?limit=&cursor=} into a {@link PageRequest} and a {@link Page} back into {@link PagedResponse}
 * (DOC-31 §5.1). {@code limit} defaults to 50 and must be in {@code [1, 500]}; a {@code cursor} must have been issued
 * for the same filters ({@link CursorCodec#fingerprint}).
 */
public final class PageParams {

    private final int defaultLimit;
    private final int maxLimit;
    private final CursorCodec codec;

    public PageParams(int defaultLimit, int maxLimit, CursorCodec codec) {
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
        this.codec = codec;
    }

    /**
     * @param fingerprint {@link CursorCodec#fingerprint} of the request's filters
     * @throws ValidationException on {@code limit} or {@code cursor}
     */
    public PageRequest resolve(@Nullable Integer limit, @Nullable String cursor, String fingerprint) {
        int effective = limit != null ? limit : defaultLimit;
        if (effective < 1 || effective > maxLimit) {
            throw ValidationException.of("limit", "must be between 1 and " + maxLimit);
        }
        if (cursor == null || cursor.isBlank()) {
            return PageRequest.first(effective);
        }
        return new PageRequest(effective, codec.decode(cursor, fingerprint));
    }

    /** The response of one page: the items mapped to DTOs and the opaque cursor of the next page, if any. */
    public <T, R> PagedResponse<R> respond(Page<T> page, Function<T, R> mapper, String fingerprint) {
        List<R> items = page.items().stream().map(mapper).toList();
        KeysetCursor cursor = page.next();
        String next = cursor != null ? codec.encode(cursor, fingerprint) : null;
        return new PagedResponse<>(items, next);
    }
}
