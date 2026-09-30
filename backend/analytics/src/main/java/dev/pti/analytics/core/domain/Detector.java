package dev.pti.analytics.core.domain;

import java.util.Locale;

/** The analyses of DOC-23 §1. The lowercase {@link #tag()} is the value of the {@code detector} metric label. */
public enum Detector {
    BUNCHING,
    DISRUPTION,
    ETA,
    OTP,
    TICKETING;

    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
