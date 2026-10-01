package dev.pti.apitest;

import dev.pti.api.etlops.application.port.BatchLineageReader;
import dev.pti.api.etlops.application.port.DeadLetterReader;
import dev.pti.api.etlops.application.port.DeadLetterStore;
import dev.pti.api.etlops.application.port.FeedVersionReader;
import dev.pti.api.etlops.application.port.JobRequestReader;
import dev.pti.api.etlops.application.port.JobRequestStore;
import dev.pti.api.etlops.application.port.JobRunReader;
import dev.pti.api.etlops.application.port.ReplayReader;
import dev.pti.api.etlops.application.port.ReplayRequestStore;
import dev.pti.api.etlops.application.port.RuntimeFlagReader;
import dev.pti.api.etlops.application.port.RuntimeFlagStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The ports of the {@code etlops} feature replaced by one {@link InMemoryEtlOps}, so that the web tests never reach for
 * a database: a request that gets past the security filters runs the real use case against memory. The transaction
 * runners and the publisher of UI events are replaced by {@link InsightFakes}. {@link ApiWebTestSupport} imports it; a
 * test that wants data autowires the {@code InMemoryEtlOps} and calls {@code reset()} first.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EtlOpsFakes {

    @Bean
    InMemoryEtlOps inMemoryEtlOps() {
        return new InMemoryEtlOps();
    }

    @Bean
    @Primary
    JobRunReader fakeJobRuns(InMemoryEtlOps etl) {
        return etl.runReader;
    }

    @Bean
    @Primary
    JobRequestReader fakeJobRequestReader(InMemoryEtlOps etl) {
        return etl.jobRequestReader;
    }

    @Bean
    @Primary
    JobRequestStore fakeJobRequestStore(InMemoryEtlOps etl) {
        return etl.jobRequestStore;
    }

    @Bean
    @Primary
    BatchLineageReader fakeLineage(InMemoryEtlOps etl) {
        return etl.lineageReader;
    }

    @Bean
    @Primary
    FeedVersionReader fakeFeeds(InMemoryEtlOps etl) {
        return etl.feedReader;
    }

    @Bean
    @Primary
    DeadLetterReader fakeDeadLetterReader(InMemoryEtlOps etl) {
        return etl.deadLetterReader;
    }

    @Bean
    @Primary
    DeadLetterStore fakeDeadLetterStore(InMemoryEtlOps etl) {
        return etl.deadLetterStore;
    }

    @Bean
    @Primary
    ReplayReader fakeReplayReader(InMemoryEtlOps etl) {
        return etl.replayReader;
    }

    @Bean
    @Primary
    ReplayRequestStore fakeReplayStore(InMemoryEtlOps etl) {
        return etl.replayStore;
    }

    @Bean
    @Primary
    RuntimeFlagReader fakeFlagReader(InMemoryEtlOps etl) {
        return etl.flagReader;
    }

    @Bean
    @Primary
    RuntimeFlagStore fakeFlagStore(InMemoryEtlOps etl) {
        return etl.flagStore;
    }
}
