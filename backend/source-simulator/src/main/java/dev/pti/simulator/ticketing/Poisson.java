package dev.pti.simulator.ticketing;

import java.util.random.RandomGenerator;

/** Poisson draws for arrival counts (DOC-25 §9.2, §7.6). */
public final class Poisson {

    private Poisson() {}

    /** Knuth's method for the usual few events per tick; a normal approximation for large means. */
    public static int sample(RandomGenerator rng, double mean) {
        if (mean <= 0) {
            return 0;
        }
        if (mean > 30) {
            return (int) Math.max(0, Math.round(rng.nextGaussian(mean, Math.sqrt(mean))));
        }
        double limit = Math.exp(-mean);
        double p = rng.nextDouble();
        int n = 0;
        while (p > limit) {
            p *= rng.nextDouble();
            n++;
        }
        return n;
    }
}
