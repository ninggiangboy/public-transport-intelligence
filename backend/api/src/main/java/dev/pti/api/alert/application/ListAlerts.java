package dev.pti.api.alert.application;

import dev.pti.api.alert.application.port.AlertReader;
import dev.pti.api.alert.domain.Alert;
import dev.pti.api.platform.domain.Caller;
import dev.pti.api.platform.domain.ForbiddenException;
import dev.pti.api.platform.domain.Page;
import dev.pti.api.platform.domain.PageRequest;
import dev.pti.api.platform.domain.WithAsOf;
import dev.pti.common.events.Audience;
import dev.pti.common.tx.TransactionRunner;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET /alerts} (DOC-32 E-20, ADR-0023). The audience of an alert is the least visibility it has: an anonymous
 * caller sees the {@code PUBLIC} ones, projected (no acknowledgement, a body cut to the allowed keys); a viewer sees
 * every audience, in full. Asking an anonymous caller for another audience is a 403, not an empty list.
 */
public final class ListAlerts {

    private static final Set<Audience> PUBLIC_ONLY = EnumSet.of(Audience.PUBLIC);

    private final AlertReader alerts;
    private final TransactionRunner tx;

    public ListAlerts(AlertReader alerts, TransactionRunner tx) {
        this.alerts = alerts;
        this.tx = tx;
    }

    /**
     * @return one page, and as its as-of the newest {@code created_at} in it (DOC-31 §7.3)
     * @throws ForbiddenException when an anonymous caller asks for an audience other than {@code PUBLIC}
     */
    public WithAsOf<Page<Alert>> execute(Caller caller, AlertQuery query, PageRequest request) {
        AlertQuery visible = query.withAudiences(audiencesFor(caller, query.audiences()));
        Page<Alert> page = tx.inTransaction(() -> alerts.list(visible, request));
        Optional<Instant> newest = page.items().stream().map(Alert::createdAt).max(Instant::compareTo);
        if (caller.isViewer()) {
            return WithAsOf.of(page, newest);
        }
        return WithAsOf.of(
                new Page<>(page.items().stream().map(Alert::forAnonymous).toList(), page.next()), newest);
    }

    private static Set<Audience> audiencesFor(Caller caller, Set<Audience> asked) {
        if (caller.isViewer()) {
            return asked.isEmpty() ? EnumSet.allOf(Audience.class) : asked;
        }
        if (!PUBLIC_ONLY.containsAll(asked)) {
            throw new ForbiddenException("Only a signed-in user may ask for alerts of another audience.");
        }
        return PUBLIC_ONLY;
    }
}
