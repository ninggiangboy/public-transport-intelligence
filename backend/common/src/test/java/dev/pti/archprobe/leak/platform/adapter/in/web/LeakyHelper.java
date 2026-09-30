package dev.pti.archprobe.leak.platform.adapter.in.web;

import dev.pti.archprobe.leak.transit.application.AnyUseCase;

/** Synthetic: the platform feature depends on another feature. Forbidden, even on its application layer. */
public class LeakyHelper {

    AnyUseCase useCase() {
        return new AnyUseCase();
    }
}
