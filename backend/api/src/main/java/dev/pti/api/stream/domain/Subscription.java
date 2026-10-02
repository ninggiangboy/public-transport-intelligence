package dev.pti.api.stream.domain;

import dev.pti.common.events.Audience;
import dev.pti.common.events.UiChannel;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a connection asked for and may see (DOC-26 §3, DOC-32 E-70).
 *
 * @param routeIds the route filter; empty means every route
 * @param authenticated whether the caller holds a valid token; anonymous callers get the public projection
 * @param tokenExpiresAt when the token expires, which closes the connection (DOC-26 §7)
 * @param clientKey {@code ip:<address>} or {@code user:<subject>}, for the connection caps of DOC-31 §11
 */
public record Subscription(
        Set<UiChannel> channels,
        Set<String> routeIds,
        boolean authenticated,
        @Nullable Instant tokenExpiresAt,
        String clientKey) {

    public Subscription {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("A subscription needs at least one channel");
        }
        channels = Set.copyOf(EnumSet.copyOf(channels));
        routeIds = Set.copyOf(routeIds);
    }

    /** DOC-26 §6.1: channel, audience and route filter. The route filter skips events without a route. */
    public boolean wants(HubEvent event) {
        if (!channels.contains(event.channel())) {
            return false;
        }
        if (!authenticated && event.audience() != Audience.PUBLIC) {
            return false;
        }
        return routeIds.isEmpty() || event.routeId() == null || routeIds.contains(event.routeId());
    }

    /** The channels a {@code resync} names after a gap: the replayed ones this connection has (DOC-26 §5). */
    public Set<UiChannel> replayedChannels() {
        EnumSet<UiChannel> replayed = EnumSet.noneOf(UiChannel.class);
        channels.stream().filter(HubEvent.REPLAYED_CHANNELS::contains).forEach(replayed::add);
        return replayed;
    }
}
