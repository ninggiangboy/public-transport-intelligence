package dev.pti.api.insight.application;

import dev.pti.api.insight.application.port.DispatchSuggestionReader;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.common.tx.TransactionRunner;

/** {@code GET /insights/dispatch-suggestions} (DOC-32 E-17): the suggestions created in a range, newest first. */
public final class ListDispatchSuggestions {

    private final DispatchSuggestionReader suggestions;
    private final TransactionRunner tx;

    public ListDispatchSuggestions(DispatchSuggestionReader suggestions, TransactionRunner tx) {
        this.suggestions = suggestions;
        this.tx = tx;
    }

    public Page<DispatchSuggestion> execute(SuggestionQuery query, PageRequest request) {
        return tx.inTransaction(() -> suggestions.list(query, request));
    }
}
