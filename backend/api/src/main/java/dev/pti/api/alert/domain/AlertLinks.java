package dev.pti.api.alert.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The link of an alert, the screen of the UI it belongs to (DOC-32 E-20, FR-14.3, DOC-34 §5.3). It is worked out when
 * the alert is read or sent, never stored, and by this one function for REST and for the UI events, so both carry the
 * same link.
 */
public final class AlertLinks {

    private static final String JOBS = "/ops/jobs";

    private AlertLinks() {}

    public static String of(Alert alert) {
        return switch (alert.type()) {
            case DISRUPTION -> map(alert, "disruption");
            case BUNCHING -> map(alert, "bunching");
            case TICKETING_ANOMALY -> ticketing(alert);
            case DLQ_SEVERE -> "/ops/dlq?severity=2&status=NEW,MANUAL,PENDING_CONFIRM";
            case FEED_STALE -> "/ops/jobs?kind=STREAM";
            case INFRA -> runbook(alert.body());
        };
    }

    private static String ticketing(Alert alert) {
        String ref = alert.refId();
        return ref == null ? "/ops/ticketing" : "/ops/ticketing?anomaly=" + ref;
    }

    /** The map of the route with the episode selected; an alert that lacks either still gets a sensible link. */
    private static String map(Alert alert, String parameter) {
        StringBuilder link = new StringBuilder("/map");
        char separator = '?';
        String route = alert.routeId();
        String ref = alert.refId();
        if (route != null) {
            link.append(separator).append("route=").append(route);
            separator = '&';
        }
        if (ref != null) {
            link.append(separator).append(parameter).append('=').append(ref);
        }
        return link.toString();
    }

    /** The runbook of the Alertmanager alert, if it names one as an absolute http(s) URL; else the jobs screen. */
    private static String runbook(Map<String, Object> body) {
        if (body.get("annotations") instanceof Map<?, ?> annotations
                && annotations.get("runbook_url") instanceof String url
                && isAbsoluteWebUrl(url)) {
            return url;
        }
        return JOBS;
    }

    private static boolean isAbsoluteWebUrl(@Nullable String text) {
        if (text == null) {
            return false;
        }
        try {
            URI uri = new URI(text.strip());
            String scheme = String.valueOf(uri.getScheme()).toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
