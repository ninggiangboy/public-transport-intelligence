package dev.pti.simulator.rate.application.port;

/** The rate multiplier gauges (DOC-28 §3.1). */
public interface RateMetrics {

    /** {@code pti_sim_rate_multiplier{stream=gtfs-rt|ticketing}}. */
    void multiplier(double gtfsRt, double ticketing);
}
