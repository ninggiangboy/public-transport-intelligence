package dev.pti.simulator.scenario;

import dev.pti.simulator.Throughput;
import dev.pti.simulator.control.ProblemHandler;
import dev.pti.simulator.control.ScenarioController;
import dev.pti.simulator.control.SimController;
import dev.pti.simulator.control.StatusService;
import dev.pti.simulator.feed.Feed;
import dev.pti.simulator.feed.Feeds;
import dev.pti.simulator.feed.ServiceDateMapper;
import dev.pti.simulator.feed.ServiceDays;
import dev.pti.simulator.ledger.Ledger;
import dev.pti.simulator.ledger.LedgerEntry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The control API over a {@link ScenarioKit}, for controller tests outside this package. */
public final class ScenarioFixture {

    private final ScenarioKit kit;
    private final Feed feed;

    private ScenarioFixture(Feed feed, Instant start) {
        this.feed = feed;
        this.kit = new ScenarioKit(feed, start);
    }

    /** The mini feed at 16:20 CDT on Tuesday 2026-09-29. */
    public static ScenarioFixture onMiniFeed() {
        return onMiniFeedAt("2026-09-29T21:20:00Z");
    }

    public static ScenarioFixture onMiniFeedAt(String instant) {
        ScenarioFixture fixture = new ScenarioFixture(Feeds.mini(), Instant.parse(instant));
        fixture.kit.harness.run(Duration.ZERO);
        return fixture;
    }

    public MockMvc mockMvc() {
        ServiceDays days =
                new ServiceDays(feed, new ServiceDateMapper(feed.calendar(), "auto"), Duration.ofMinutes(10));
        Throughput throughput =
                new Throughput(() -> kit.harness.clock().realNow().toEpochMilli());
        StatusService status = new StatusService(
                kit.harness.clock(),
                feed,
                days,
                kit.harness.rate(),
                kit.harness.emitter(),
                throughput,
                new IdleLedger(),
                kit.engine);
        ScenarioCatalog catalog = new ScenarioCatalog(
                ScenarioKit.MAPPER, ScenarioKit.MAX_DURATION, () -> List.of("KIOSK-001", "KIOSK-002"));
        return MockMvcBuilders.standaloneSetup(
                        new SimController(status, kit.harness.rate(), kit.engine),
                        new ScenarioController(kit.engine, catalog))
                .setControllerAdvice(new ProblemHandler())
                .build();
    }

    private static final class IdleLedger implements Ledger {

        @Override
        public void record(LedgerEntry entry, String topic, int partition, long offset) {}

        @Override
        public int queueDepth() {
            return 0;
        }

        @Override
        public @Nullable Instant lastFlushAt() {
            return null;
        }
    }
}
