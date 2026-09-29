package dev.pti.etl.stream;

import dev.pti.etl.core.EtlSource;

/** Topic names with the test prefix of {@code pti.kafka.topic-prefix} (DOC-44 §6.2). */
public record TopicNames(String prefix) {

    public String of(EtlSource source) {
        return prefix + source.requireTopic();
    }

    /** For {@code @KafkaListener(topics = "#{@topicNames.named('...')}")}. */
    public String named(String source) {
        return of(EtlSource.valueOf(source));
    }
}
