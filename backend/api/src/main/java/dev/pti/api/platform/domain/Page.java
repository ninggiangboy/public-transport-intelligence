package dev.pti.api.platform.domain;

import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One page of a keyset-paged list: the items, and the cursor of the next page when there is one (DOC-31 §5.1). Built
 * from the {@code limit + 1} rows a repository reads; there is no total count.
 */
public record Page<T>(List<T> items, @Nullable KeysetCursor next) {

    public Page {
        items = List.copyOf(items);
    }

    /**
     * @param fetched up to {@code request.fetchSize()} rows in sort order
     * @param keyOf the sort key of a row, as strings, in the order the cursor compares them
     */
    public static <T> Page<T> of(PageRequest request, List<T> fetched, Function<T, List<String>> keyOf) {
        if (fetched.size() <= request.limit()) {
            return new Page<>(fetched, null);
        }
        List<T> items = fetched.subList(0, request.limit());
        return new Page<>(items, new KeysetCursor(keyOf.apply(items.get(items.size() - 1))));
    }

    public boolean hasNext() {
        return next != null;
    }
}
