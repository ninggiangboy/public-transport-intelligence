package dev.pti.simulator.control;

/** {@code PUT /sim/rate} while {@code load-ramp} runs: answered with 409 {@code load-ramp-running} (DOC-25 §8). */
public class LoadRampRunningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LoadRampRunningException() {
        super("A load-ramp scenario is setting the rate; stop it first.");
    }
}
