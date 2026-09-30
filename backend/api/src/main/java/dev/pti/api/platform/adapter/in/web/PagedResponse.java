package dev.pti.api.platform.adapter.in.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** A keyset-paged list (DOC-31 §5.1): {@code nextCursor} is absent on the last page. */
public record PagedResponse<T>(List<T> items, @Nullable String nextCursor) {}
