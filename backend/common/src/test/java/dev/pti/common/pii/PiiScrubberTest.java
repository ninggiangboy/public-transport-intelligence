package dev.pti.common.pii;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.common.json.MessageJson;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** DOC-18 §7: L-06. */
@DisplayName("L-06 PiiScrubber")
class PiiScrubberTest {

    private final PiiScrubber scrubber = PiiScrubber.withDefaults();

    @Test
    void removesBlockedKeyFromValidJson() {
        String payload = "{\"transaction_id\":\"t1\",\"customer_ref\":\"c-000184223\",\"amount\":\"2.50\"}";

        String scrubbed = scrubber.scrub(payload);

        assertThat(scrubbed).doesNotContain("customer_ref").doesNotContain("c-000184223");
        assertThat(MessageJson.mapper().readTree(scrubbed).path("amount").asString())
                .isEqualTo("2.50");
    }

    @Test
    void removesBlockedKeyAtAnyDepth() {
        String payload = "{\"a\":{\"b\":[{\"customer_ref\":\"c-1\",\"x\":1}],\"customer_ref\":\"c-2\"}}";

        assertThat(scrubber.scrub(payload)).isEqualTo("{\"a\":{\"b\":[{\"x\":1}]}}");
    }

    @Test
    void redactsBlockedValueInBrokenJson() {
        String payload = "{\"transaction_id\":\"t1\", \"customer_ref\" : \"c-000184223\", \"amount\":";

        assertThat(scrubber.scrub(payload))
                .isEqualTo("{\"transaction_id\":\"t1\", \"customer_ref\":\"[REDACTED]\", \"amount\":");
    }

    @Test
    void keepsBrokenJsonWithoutBlockedKey() {
        String payload = "{\"vehicle_id\":\"2050\", \"lat\":";

        assertThat(scrubber.scrub(payload)).isSameAs(payload);
    }

    @Test
    void keepsJsonWithoutBlockedKeyByteForByte() {
        String payload = "{ \"lat\" : 44.908789000000001 , \"note\":\"customer_reference is fine\" }";

        assertThat(scrubber.scrub(payload)).isSameAs(payload);
    }

    @Test
    void usesTheConfiguredBlocklist() {
        PiiScrubber custom = new PiiScrubber(List.of("email", "customer_ref"));

        assertThat(custom.scrub("{\"email\":\"a@b.c\",\"customer_ref\":\"c\",\"k\":1}"))
                .isEqualTo("{\"k\":1}");
        assertThat(custom.containsBlocked(MessageJson.mapper().readTree("[{\"x\":{\"email\":1}}]")))
                .isTrue();
        assertThat(custom.containsBlocked(MessageJson.mapper().readTree("{\"x\":[1,2]}")))
                .isFalse();
    }
}
