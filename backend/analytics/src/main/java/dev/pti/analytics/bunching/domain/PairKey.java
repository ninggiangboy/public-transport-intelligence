package dev.pti.analytics.bunching.domain;

/** The identity of a pair on a route: the vehicle ahead and the vehicle right behind it. */
public record PairKey(String leader, String follower) {}
