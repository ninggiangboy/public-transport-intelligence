package dev.pti.etl.gtfs.application;

import java.util.List;
import java.util.Set;

/** What a feed archive held: the known GTFS files extracted from the zip and the entries left out (GV-14). */
public record ExtractedFeed(Set<String> files, List<String> ignored) {}
