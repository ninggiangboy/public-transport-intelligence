package dev.pti.api.transit.adapter.in.web;

import dev.pti.api.system.application.port.FreshnessSnapshots;
import dev.pti.api.system.domain.FreshnessSnapshot;
import dev.pti.api.system.domain.SourceKind;
import dev.pti.api.testing.JwtFixture;
import dev.pti.apitest.ApiWebTestSupport;
import dev.pti.apitest.InMemoryTransit;
import dev.pti.common.time.BusinessClock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The base of the transit web tests: the whole application over MockMvc with the in-memory ports of {@code
 * TransitFakes}, emptied before each test, and helpers to set what the freshness probe last saw (the source of {@code
 * X-Data-As-Of}) and to sign in.
 */
@ResourceLock(TransitWebTest.FAKE_TRANSIT)
@ResourceLock(TransitWebTest.PROBE_RESULT)
abstract class TransitWebTest extends ApiWebTestSupport {

    /** Test classes run concurrently; the in-memory ports are shared by the context, so these classes take turns. */
    static final String FAKE_TRANSIT = "in-memory-transit";

    /** The result of the freshness probe is one value per context; the tests that store it take turns. */
    static final String PROBE_RESULT = "freshness-probe-result";

    @Autowired
    protected InMemoryTransit transit;

    @Autowired
    protected FreshnessSnapshots snapshots;

    @Autowired
    protected BusinessClock clock;

    @BeforeEach
    void emptyTransit() {
        transit.reset();
    }

    /** The probe result that {@code X-Data-As-Of} is read from; a {@code null} source has never had data. */
    protected void probe(
            @Nullable Instant vehiclePosition, @Nullable Instant tripUpdate, @Nullable Instant etaComputedAt) {
        Map<SourceKind, Instant> lastEventAt = new EnumMap<>(SourceKind.class);
        if (vehiclePosition != null) {
            lastEventAt.put(SourceKind.GTFS_RT_VEHICLE_POSITION, vehiclePosition);
        }
        if (tripUpdate != null) {
            lastEventAt.put(SourceKind.GTFS_RT_TRIP_UPDATE, tripUpdate);
        }
        snapshots.store(new FreshnessSnapshot(
                clock.instant(), InMemoryTransit.FEED, lastEventAt, etaComputedAt, etaComputedAt, null, false));
    }

    protected static String viewer() {
        return JwtFixture.bearer(JwtFixture.viewer());
    }

    protected static String operator() {
        return JwtFixture.bearer(JwtFixture.operator());
    }
}
