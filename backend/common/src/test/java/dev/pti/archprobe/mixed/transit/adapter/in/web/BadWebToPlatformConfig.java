package dev.pti.archprobe.mixed.transit.adapter.in.web;

import dev.pti.archprobe.mixed.platform.config.PlatformConfig;

/** Synthetic: an inbound adapter that reaches the platform's config. Forbidden. */
public class BadWebToPlatformConfig {

    PlatformConfig config() {
        return new PlatformConfig();
    }
}
