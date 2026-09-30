package dev.pti.archprobe.mixed.transit.adapter.out.jdbc;

import dev.pti.archprobe.mixed.platform.adapter.out.jdbc.SqlFiles;
import dev.pti.archprobe.mixed.platform.domain.Page;

/** Synthetic: an outbound adapter that uses the platform's outbound adapter and domain. Allowed. */
public class AllowedRepository {

    SqlFiles files() {
        return new SqlFiles();
    }

    Page page() {
        return new Page();
    }
}
