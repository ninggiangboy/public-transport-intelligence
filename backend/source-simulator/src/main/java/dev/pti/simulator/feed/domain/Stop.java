package dev.pti.simulator.feed.domain;

/** A stop the simulator can place a vehicle at. */
public record Stop(String id, String name, double lat, double lon) {}
