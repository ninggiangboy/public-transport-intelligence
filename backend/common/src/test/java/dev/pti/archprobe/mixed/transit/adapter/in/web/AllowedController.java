package dev.pti.archprobe.mixed.transit.adapter.in.web;

import dev.pti.archprobe.mixed.platform.adapter.in.web.ProblemHelper;
import dev.pti.archprobe.mixed.platform.domain.Page;

/** Synthetic: an inbound adapter that uses the platform's inbound adapter and domain. Allowed. */
public class AllowedController {

    ProblemHelper helper() {
        return new ProblemHelper();
    }

    Page page() {
        return new Page();
    }
}
