package dev.pti.etl.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.error.DeserializationException;
import dev.pti.etl.rules.RuleContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CoreTypesTest {

    private static InboundMessage message(String value) {
        return new InboundMessage(
                EtlSource.TICKETING_SALES,
                "k",
                value.getBytes(StandardCharsets.UTF_8),
                "ticketing.sales.cdc",
                1,
                2L,
                Instant.EPOCH,
                Map.of("h", "v"));
    }

    @Test
    void messagesCompareTheirBytes() {
        assertThat(message("a")).isEqualTo(message("a")).hasSameHashCodeAs(message("a"));
        assertThat(message("a")).isNotEqualTo(message("b"));
        assertThat(message("a").toString()).isEqualTo("InboundMessage[TICKETING_SALES ticketing.sales.cdc-1@2]");
        assertThat(message("a").isTombstone()).isFalse();
    }

    @Test
    void sourcesKnowTheirTopics() {
        assertThat(EtlSource.ofTopic("gtfs.trip_updates")).contains(EtlSource.GTFS_RT_TRIP_UPDATE);
        assertThat(EtlSource.ofTopic("other")).isEmpty();
        assertThat(EtlSource.GTFS_STATIC.topic()).isEmpty();
        assertThatThrownBy(EtlSource.GTFS_STATIC::requireTopic).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void utf8() {
        assertThat(Utf8.decode("é".getBytes(StandardCharsets.UTF_8))).isEqualTo("é");
        assertThatThrownBy(() -> Utf8.decode(new byte[] {(byte) 0xC3})).isInstanceOf(DeserializationException.class);
        assertThat(Utf8.forStorage(new byte[] {'a', 0})).isEqualTo("a�");
    }

    @Test
    void processorsAreRoutedBySource() {
        MessageProcessor sales = new MessageProcessor() {
            @Override
            public EtlSource source() {
                return EtlSource.TICKETING_SALES;
            }

            @Override
            public WriteSet process(InboundMessage message, RuleContext context) {
                return WriteSet.empty(message);
            }

            @Override
            public @Nullable String businessKey(InboundMessage message) {
                return null;
            }
        };
        MessageProcessors processors = new MessageProcessors(List.of(sales));
        assertThat(processors.forSource(EtlSource.TICKETING_SALES)).isSameAs(sales);
        assertThatThrownBy(() -> processors.forSource(EtlSource.GTFS_STATIC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MessageProcessors(List.of(sales, sales)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anEmptyWriteSetHasNoKafkaPositionWithoutATopic() {
        WriteSet empty =
                WriteSet.empty(new InboundMessage(EtlSource.GTFS_STATIC, null, null, null, null, null, null, Map.of()));
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.kafkaPosition()).isNull();
        assertThat(empty.offsetOrZero()).isZero();
        assertThat(WriteSet.empty(message("x")).kafkaPosition()).isEqualTo("ticketing.sales.cdc-1@2");
    }
}
