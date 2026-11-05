package dev.pti.etl.write.domain;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** The pure decision behind {@code KnownKeyCache}: which keys are still unknown (DOC-20 §4.5). */
public final class KnownKeys {

    private KnownKeys() {}

    public static List<String> unknown(Collection<String> ids, Predicate<String> known) {
        return ids.stream().filter(id -> !known.test(id)).distinct().sorted().toList();
    }
}
