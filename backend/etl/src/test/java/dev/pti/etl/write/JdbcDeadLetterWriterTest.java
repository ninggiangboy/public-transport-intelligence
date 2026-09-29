package dev.pti.etl.write;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.pii.PiiScrubber;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** The payload and message limits of DOC-22 §1.2. */
class JdbcDeadLetterWriterTest {

    private final JdbcDeadLetterWriter writer = new JdbcDeadLetterWriter(
            new NamedParameterJdbcTemplate(new JdbcTemplate()),
            PiiScrubber.withDefaults(),
            100,
            new SimpleMeterRegistry());

    @Test
    void aTombstoneIsStoredAsEmptyText() {
        assertThat(writer.payload(null)).isEmpty();
    }

    @Test
    void theCustomerReferenceIsScrubbed() {
        String payload = writer.payload("{\"a\":1,\"customer_ref\":\"cust-1\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(payload).doesNotContain("cust-1").contains("\"a\"");
    }

    @Test
    void nulAndInvalidBytesAreReplaced() {
        String payload = writer.payload(new byte[] {'a', 0, (byte) 0xFF, 'b'});
        assertThat(payload).doesNotContain("\u0000").startsWith("a").endsWith("b");
    }

    @Test
    void aLongPayloadIsCutOnACharacterBoundaryAndSaysHowMuch() {
        String text = "é".repeat(200);
        String payload = writer.payload(text.getBytes(StandardCharsets.UTF_8));
        assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(100);
        assertThat(payload).matches("é+…\\[truncated \\d+ bytes]");
        int kept = payload.indexOf('…') * 2;
        assertThat(payload).endsWith("[truncated " + (400 - kept) + " bytes]");
    }

    @Test
    void theErrorMessageIsCutAt4000Characters() {
        assertThat(JdbcDeadLetterWriter.errorMessage("x".repeat(5000)))
                .hasSize(4000)
                .endsWith("…");
        assertThat(JdbcDeadLetterWriter.errorMessage("bad\u0000value")).isEqualTo("bad�value");
    }
}
