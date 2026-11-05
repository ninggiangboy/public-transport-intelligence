package dev.pti.simulator.emit.application.port;

/** The emitter gauges (DOC-28 §3.1). The emitter reports the values on each tick. */
public interface EmitterMetrics {

    /** {@code pti_sim_active_vehicles} and {@code pti_sim_active_trips}. */
    void active(int vehicles, int trips);

    /** {@code pti_sim_synthetic_vehicles}: vehicles that are on a synthetic block. */
    void syntheticVehicles(int count);

    /** {@code pti_sim_tick_lag_seconds}: how far behind the emitter is running. */
    void tickLag(double seconds);
}
