package dev.pti.etl.stream.application.port;

import dev.pti.etl.stream.application.StreamListener;
import java.util.Locale;
import java.util.Set;

/**
 * The pause state of the stream listeners, read by the health indicators (DOC-20 §6.1). The warehouse circuit and
 * the runtime flags both pause a container; the tag is the {@code reason} label of {@code pti_etl_listener_paused}
 * (DOC-20 §12).
 */
public interface ListenerPauseState {

    /** Whether the listener container is running on this pod. */
    boolean isRunning(StreamListener listener);

    /** The reasons the listener is paused for, empty when it runs freely. */
    Set<Reason> reasons(StreamListener listener);

    /** Why a container is paused. */
    enum Reason {
        FLAG,
        CIRCUIT,
        BACKOFF;

        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
