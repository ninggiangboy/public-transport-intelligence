package dev.pti.api.transit.application.port;

import dev.pti.api.transit.domain.EtaRow;
import java.util.List;

/** The historical ETA rows of a route for one weekday and hour, one per stop that has history (DOC-32 E-04). */
public interface EtaProfileReader {

    /**
     * @param dayOfWeek ISO, 1 = Monday to 7 = Sunday, in the timezone of the feed
     * @param hourOfDay 0-23, in the timezone of the feed
     */
    List<EtaRow> read(String routeId, int dayOfWeek, int hourOfDay);
}
