package dev.pti.archprobe.mixed.transit.application;

import dev.pti.archprobe.mixed.platform.adapter.in.web.ProblemHelper;

/** Synthetic: a use case that reaches the platform's adapter. Forbidden: only adapters may. */
public class BadUseCaseToPlatformAdapter {

    ProblemHelper helper() {
        return new ProblemHelper();
    }
}
