package dev.pti.etl.stream;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StreamChunkRequestTest {

    private static InboundMessage at(int partition, long offset, Instant timestamp) {
        return new InboundMessage(
                EtlSource.GTFS_RT_TRIP_UPDATE,
                "k",
                new byte[0],
                "gtfs.trip_updates",
                partition,
                offset,
                timestamp,
                Map.of());
    }

    @Test
    void offsetsAreTheRangePerPartitionAndTheOldestRecordIsKnown() {
        Instant t0 = Instant.parse("2026-09-29T21:00:00Z");
        StreamChunkRequest request = new StreamChunkRequest(
                UUID.randomUUID(),
                EtlSource.GTFS_RT_TRIP_UPDATE,
                "gtfs-rt-trip-update",
                "pti-etl-gtfs-rt",
                "pod",
                List.of(
                        at(3, 12, t0.plusSeconds(2)),
                        at(3, 10, t0.plusSeconds(1)),
                        at(7, 5, t0),
                        new InboundMessage(
                                EtlSource.GTFS_RT_TRIP_UPDATE, null, null, null, null, null, null, Map.of())),
                false);

        assertThat(request.offsets()).containsOnlyKeys("gtfs.trip_updates-3", "gtfs.trip_updates-7");
        assertThat(request.offsets().get("gtfs.trip_updates-3")).containsExactly(10, 12);
        assertThat(request.offsets().get("gtfs.trip_updates-7")).containsExactly(5, 5);
        assertThat(request.minRecordTimestamp()).contains(t0);
    }
}
