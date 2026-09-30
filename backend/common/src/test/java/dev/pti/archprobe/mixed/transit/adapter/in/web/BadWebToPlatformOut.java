package dev.pti.archprobe.mixed.transit.adapter.in.web;

import dev.pti.archprobe.mixed.platform.adapter.out.jdbc.SqlFiles;

/** Synthetic: an inbound adapter that reaches the platform's outbound adapter. Forbidden (A-14 and A-15). */
public class BadWebToPlatformOut {

    SqlFiles files() {
        return new SqlFiles();
    }
}
