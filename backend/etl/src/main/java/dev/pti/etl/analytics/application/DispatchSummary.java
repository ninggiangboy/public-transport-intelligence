package dev.pti.etl.analytics.application;

/** How many units of analytics work a dispatch ran and how many of them failed. */
public record DispatchSummary(int runs, int errors) {

    public static final DispatchSummary NONE = new DispatchSummary(0, 0);
}
