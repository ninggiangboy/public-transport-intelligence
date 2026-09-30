package dev.pti.archprobe.mixed.transit.adapter.in.web;

import dev.pti.archprobe.mixed.insight.adapter.in.web.InsightController;

/** Synthetic: an inbound adapter that reaches the inbound adapter of another ordinary feature. Forbidden. */
public class BadWebToOtherFeature {

    InsightController controller() {
        return new InsightController();
    }
}
