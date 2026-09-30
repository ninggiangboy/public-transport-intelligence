package dev.pti.api.platform.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.platform.domain.ApiException.FieldError;
import dev.pti.api.platform.domain.KeysetCursor;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.ValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The opaque keyset cursor and the paging parameters (DOC-31 §5.1, AG-02…AG-04). */
class CursorCodecTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final CursorCodec codec = new CursorCodec(MAPPER);
    private final PageParams params = new PageParams(50, 500, codec);

    private static String token(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A cursor is base64url JSON {v, k, f} without padding and decodes to the same keys")
    void roundTrip() {
        String fingerprint = CursorCodec.fingerprint(Map.of("routeId", "18"));
        KeysetCursor cursor = KeysetCursor.of("2026-09-29T21:19:30Z", "c69bcc55-0000-7000-8000-000000000001");

        String encoded = codec.encode(cursor, fingerprint);

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertThat(json)
                .isEqualTo("{\"v\":1,\"k\":[\"2026-09-29T21:19:30Z\",\"c69bcc55-0000-7000-8000-000000000001\"],\"f\":\""
                        + fingerprint + "\"}");
        assertThat(codec.decode(encoded, fingerprint)).isEqualTo(cursor);
    }

    @Test
    void fingerprintIsFourHexCharactersAndIgnoresOrderAndEmptyFilters() {
        String a = CursorCodec.fingerprint(Map.of("status", List.of("NEW", "MANUAL"), "routeId", "18"));
        String b = CursorCodec.fingerprint(Map.of("routeId", "18", "status", List.of("MANUAL", "NEW"), "source", ""));

        assertThat(a).matches("[0-9a-f]{4}").isEqualTo(b);
        assertThat(CursorCodec.fingerprint(Map.of("status", "NEW")))
                .isNotEqualTo(CursorCodec.fingerprint(Map.of("status", "MANUAL")));
    }

    @Test
    void numbersAsKeysAreKeptAsText() {
        String encoded = token("{\"v\":1,\"k\":[12,\"a\"],\"f\":\"abcd\"}");

        assertThat(codec.decode(encoded, "abcd").keys()).containsExactly("12", "a");
    }

    @ParameterizedTest(name = "a cursor of {0} is a 400 on the field cursor")
    @ValueSource(
            strings = {
                "%%%not-base64",
                "e30",
                "W10",
                "eyJ2IjoyLCJrIjpbImEiXSwiZiI6ImFiY2QifQ",
                "eyJ2IjoxLCJrIjpbXSwiZiI6ImFiY2QifQ",
                "eyJ2IjoxLCJrIjpbeyJhIjoxfV0sImYiOiJhYmNkIn0",
                "eyJ2IjoxLCJrIjoiYSIsImYiOiJhYmNkIn0"
            })
    void invalidCursors(String token) {
        assertThatThrownBy(() -> codec.decode(token, "abcd")).isInstanceOfSatisfying(ValidationException.class, e -> {
            assertThat(e.errors()).extracting(FieldError::field).containsExactly("cursor");
            assertThat(e.errors().get(0).message()).isEqualTo("is not a valid cursor");
        });
    }

    @Test
    @DisplayName("AG-03 a cursor of one filter set does not open another")
    void fingerprintMismatch() {
        String encoded = codec.encode(KeysetCursor.of("k"), CursorCodec.fingerprint(Map.of("status", "NEW")));

        assertThatThrownBy(() -> codec.decode(encoded, CursorCodec.fingerprint(Map.of("status", "MANUAL"))))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.errors().get(0).field()).isEqualTo("cursor");
                    assertThat(e.errors().get(0).message()).isEqualTo("does not belong to this query");
                });
    }

    @Test
    void limitDefaultsToFifty() {
        PageRequest request = params.resolve(null, null, "abcd");

        assertThat(request.limit()).isEqualTo(50);
        assertThat(request.after()).isNull();
    }

    @ParameterizedTest(name = "AG-04 limit={0} is refused")
    @ValueSource(ints = {0, -5, 501})
    void limitOutOfRange(int limit) {
        assertThatThrownBy(() -> params.resolve(limit, null, "abcd"))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.errors().get(0).field()).isEqualTo("limit");
                });
    }

    @Test
    void limitBoundsAreAccepted() {
        assertThat(params.resolve(1, null, "abcd").limit()).isEqualTo(1);
        assertThat(params.resolve(500, null, "abcd").limit()).isEqualTo(500);
    }

    @Test
    @DisplayName("AG-02 120 rows at limit 50 page as 50, 50 and 20 through the codec, with no gap or duplicate")
    void pagesThroughAllRows() {
        List<Integer> all = IntStream.range(0, 120).boxed().toList();
        String fingerprint = CursorCodec.fingerprint(Map.of());
        String cursor = null;
        java.util.ArrayList<Integer> seen = new java.util.ArrayList<>();
        java.util.ArrayList<Integer> sizes = new java.util.ArrayList<>();
        do {
            PageRequest request = params.resolve(50, cursor, fingerprint);
            int from = request.after() == null
                    ? 0
                    : Integer.parseInt(request.after().keys().get(0)) + 1;
            List<Integer> fetched = all.subList(from, Math.min(120, from + request.fetchSize()));
            PagedResponse<Integer> response = params.respond(
                    Page.of(request, fetched, row -> List.of(String.valueOf(row))), row -> row, fingerprint);
            seen.addAll(response.items());
            sizes.add(response.items().size());
            cursor = response.nextCursor();
        } while (cursor != null);

        assertThat(sizes).containsExactly(50, 50, 20);
        assertThat(seen).isEqualTo(all);
    }
}
