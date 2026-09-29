package dev.pti.etl.replay;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One raw zone object, {@code <topic>/dt=<d>/hh=<h>/<topic>-<partition>-<start_offset>.json.gz} (DOC-18 §2). The
 * partition is only in the name; lines do not carry it.
 */
public record RawObjectKey(String key, int partition, long startOffset) {

    /** Objects of one hour in reading order: by partition, then by start offset (DOC-22 §4.3). */
    public static final Comparator<RawObjectKey> READING_ORDER =
            Comparator.comparingInt(RawObjectKey::partition).thenComparingLong(RawObjectKey::startOffset);

    public static Optional<RawObjectKey> parse(String key, String topic) {
        String name = key.substring(key.lastIndexOf('/') + 1);
        Matcher m = Pattern.compile(Pattern.quote(topic) + "-(\\d+)-(\\d+)\\.json\\.gz")
                .matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new RawObjectKey(key, Integer.parseInt(m.group(1)), Long.parseLong(m.group(2))));
    }
}
