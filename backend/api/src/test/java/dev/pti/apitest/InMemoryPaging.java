package dev.pti.apitest;

import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors;
import dev.pti.api.platform.adapter.out.jdbc.TimeIdCursors.TimeId;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Keyset paging of an in-memory list in the order the SQL uses: a time descending, then the id descending. */
final class InMemoryPaging {

    private InMemoryPaging() {}

    static <T> Page<T> page(List<T> rows, PageRequest request, Function<T, Instant> time, Function<T, UUID> id) {
        Comparator<T> newestFirst = Comparator.comparing(time).thenComparing(id).reversed();
        TimeId after = TimeIdCursors.parse(request.after());
        List<T> fetched = rows.stream()
                .sorted(newestFirst)
                .filter(row -> after == null || isAfter(time.apply(row), id.apply(row), after))
                .limit(request.fetchSize())
                .toList();
        return Page.of(
                request,
                fetched,
                row -> TimeIdCursors.of(time.apply(row), id.apply(row)).keys());
    }

    private static boolean isAfter(Instant time, UUID id, TimeId cursor) {
        int byTime = time.compareTo(cursor.time());
        return byTime < 0 || (byTime == 0 && id.compareTo(cursor.id()) < 0);
    }
}
