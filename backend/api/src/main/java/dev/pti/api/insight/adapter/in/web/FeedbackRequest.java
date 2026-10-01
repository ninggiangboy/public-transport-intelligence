package dev.pti.api.insight.adapter.in.web;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /insights/dispatch-suggestions/{id}/feedback} (DOC-32 E-18): {@code "accepted"} for the
 * Accept button or {@code "ignored"} for Dismiss, as in the column. A member that is not known is a 400 (DOC-31 §3).
 */
public record FeedbackRequest(@NotBlank String feedback) {}
