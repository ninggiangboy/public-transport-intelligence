package dev.pti.api.insight.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** What an operator says about a dispatch suggestion (DOC-32 E-18): the two values of the column, lowercase as stored. */
public enum Feedback {
    ACCEPTED("accepted"),
    IGNORED("ignored");

    private final String wireName;

    Feedback(String wireName) {
        this.wireName = wireName;
    }

    /** The value in the database and in the API. */
    public String wireName() {
        return wireName;
    }

    public static Optional<Feedback> fromWireName(String text) {
        return Arrays.stream(values())
                .filter(value -> value.wireName.equals(text))
                .findFirst();
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(Feedback::wireName).toList();
    }
}
