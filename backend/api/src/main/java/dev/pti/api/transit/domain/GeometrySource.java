package dev.pti.api.transit.domain;

/** Where the line of a direction comes from (DOC-32 E-02). */
public enum GeometrySource {
    /** The {@code shapes.txt} entry of the chosen shape. */
    SHAPE,
    /** The feed has no shape for the direction: the line joins the stops of the representative trip. */
    STOPS
}
