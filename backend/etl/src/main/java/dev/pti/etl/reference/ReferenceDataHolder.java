package dev.pti.etl.reference;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** The one {@link ReferenceData} of this JVM (DOC-21 §6.2), replaced as a whole on a feed change. */
public class ReferenceDataHolder {

    private final AtomicReference<ReferenceData> current = new AtomicReference<>();

    public Optional<ReferenceData> current() {
        return Optional.ofNullable(current.get());
    }

    void set(ReferenceData data) {
        current.set(data);
    }

    public boolean isLoaded() {
        return current.get() != null;
    }
}
