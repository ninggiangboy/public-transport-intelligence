package dev.pti.common.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TimestampsTest {

    @Test
    void formatsWithExactlyThreeFractionDigits() {
        assertThat(Timestamps.format(Instant.parse("2026-09-29T21:19:05Z"))).isEqualTo("2026-09-29T21:19:05.000Z");
        assertThat(Timestamps.format(Instant.parse("2026-09-29T21:19:05.4129Z")))
                .isEqualTo("2026-09-29T21:19:05.412Z");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026-09-29T21:19:05Z",
                "2026-09-29T21:19:05.000Z",
                "2026-09-29T21:19:05.000000Z",
                "2026-09-29T16:19:05-05:00",
                "2026-09-29T21:19:05+00:00"
            })
    void normalizesEquivalentTimestamps(String text) {
        assertThat(Timestamps.normalize(text)).isEqualTo("2026-09-29T21:19:05.000Z");
    }

    @Test
    void rejectsTimestampsWithoutOffset() {
        assertThatThrownBy(() -> Timestamps.parse("2026-09-29T21:19:05")).isInstanceOf(DateTimeParseException.class);
    }
}
