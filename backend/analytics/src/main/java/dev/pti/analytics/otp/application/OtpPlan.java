package dev.pti.analytics.otp.application;

import java.time.LocalDate;
import java.util.List;

/**
 * The service dates an OTP run scores.
 *
 * @param status whether there is anything to do
 * @param serviceDates newest first; empty when there is no feed
 */
public record OtpPlan(Status status, List<LocalDate> serviceDates) {

    public enum Status {
        /** No feed is ACTIVE: DOC-23 §15 has every analytics job do nothing then. */
        NO_FEED,
        RUN
    }

    public OtpPlan {
        serviceDates = List.copyOf(serviceDates);
    }

    public static OtpPlan noFeed() {
        return new OtpPlan(Status.NO_FEED, List.of());
    }

    public static OtpPlan run(List<LocalDate> serviceDates) {
        return new OtpPlan(Status.RUN, serviceDates);
    }
}
