package dev.pti.api.alert.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** The {@code state} filter of the alert list (DOC-32 E-20). */
public enum AlertState {
    /** Every alert. */
    ALL("all"),
    /** Not resolved yet. */
    OPEN("open"),
    /** Not resolved and not acknowledged. */
    UNACKNOWLEDGED("unacknowledged");

    private final String wireName;

    AlertState(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<AlertState> fromWireName(String text) {
        return Arrays.stream(values())
                .filter(state -> state.wireName.equals(text))
                .findFirst();
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(AlertState::wireName).toList();
    }
}
