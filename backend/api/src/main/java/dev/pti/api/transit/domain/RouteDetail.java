package dev.pti.api.transit.domain;

import java.util.List;
import java.util.Optional;

/** A route with its directions (DOC-32 E-02). The stops of a direction are the pattern E-04 is listed by. */
public record RouteDetail(long feedVersionId, RouteSummary route, List<DirectionPattern> directions) {

    public RouteDetail {
        directions = List.copyOf(directions);
    }

    public Optional<DirectionPattern> direction(int directionId) {
        return directions.stream()
                .filter(direction -> direction.directionId() == directionId)
                .findFirst();
    }
}
