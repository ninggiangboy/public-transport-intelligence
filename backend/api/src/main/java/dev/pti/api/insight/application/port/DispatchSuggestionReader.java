package dev.pti.api.insight.application.port;

import dev.pti.api.insight.application.SuggestionQuery;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;

/** Reads dispatch suggestions (DOC-32 E-17). */
public interface DispatchSuggestionReader {

    /** Newest {@code created_at} first, then {@code id} descending. */
    Page<DispatchSuggestion> list(SuggestionQuery query, PageRequest request);
}
