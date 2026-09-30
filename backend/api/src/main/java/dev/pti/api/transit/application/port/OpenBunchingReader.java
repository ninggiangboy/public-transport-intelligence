package dev.pti.api.transit.application.port;

import dev.pti.api.transit.domain.OpenBunching;
import java.util.List;

/** The bunching episodes that are open now (DOC-32 E-05). */
public interface OpenBunchingReader {

    List<OpenBunching> findOpen();
}
