package dev.pti.archprobe.mixed.transit.application;

import dev.pti.archprobe.mixed.platform.domain.Page;

/** Synthetic: a use case that uses the platform's domain. Allowed. */
public class AllowedUseCase {

    Page page() {
        return new Page();
    }
}
