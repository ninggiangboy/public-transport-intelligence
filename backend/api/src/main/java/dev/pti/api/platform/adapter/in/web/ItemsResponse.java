package dev.pti.api.platform.adapter.in.web;

import java.util.List;

/** A small list, whole (DOC-31 §5.2): no {@code limit} and no cursor. */
public record ItemsResponse<T>(List<T> items) {}
