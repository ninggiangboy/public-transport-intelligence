package dev.pti.api.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PagingTest {

    private static List<Integer> rows(int count) {
        return IntStream.range(0, count).boxed().toList();
    }

    @Test
    @DisplayName("A page reads limit + 1 rows: the extra one says another page follows and is not returned")
    void fullPageHasACursorFromItsLastRow() {
        PageRequest request = PageRequest.first(3);

        Page<Integer> page = Page.of(request, rows(request.fetchSize()), row -> List.of("k" + row));

        assertThat(request.fetchSize()).isEqualTo(4);
        assertThat(page.items()).containsExactly(0, 1, 2);
        assertThat(page.next()).isEqualTo(KeysetCursor.of("k2"));
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    void lastPageHasNoCursor() {
        Page<Integer> page = Page.of(PageRequest.first(3), rows(3), row -> List.of("k" + row));

        assertThat(page.items()).hasSize(3);
        assertThat(page.next()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void emptyPage() {
        Page<Integer> page = Page.of(PageRequest.first(50), List.of(), row -> List.of("k"));

        assertThat(page.items()).isEmpty();
        assertThat(page.next()).isNull();
    }

    @Test
    void requestRejectsALimitBelowOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> PageRequest.first(0));
    }

    @Test
    void cursorNeedsAKeyAndIsImmutable() {
        assertThatIllegalArgumentException().isThrownBy(() -> new KeysetCursor(List.of()));
        assertThat(KeysetCursor.of("a", "b").keys()).containsExactly("a", "b");
    }
}
