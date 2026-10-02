package dev.pti.etl.realtime.adapter.in.event;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.VehiclePositionRow;
import dev.pti.etl.realtime.application.VehiclesBatchPublisher;
import dev.pti.etl.realtime.domain.VehiclePosition;
import dev.pti.etl.stream.MicroBatchCommitted;
import java.util.List;
import org.springframework.context.event.EventListener;

/**
 * Hands the VehiclePosition rows of every committed poll to {@link VehiclesBatchPublisher} (DOC-20 §8). It runs on
 * the listener thread right after the commit, and only copies rows into memory, so it adds nothing to the poll.
 */
public final class VehiclePositionsListener {

    private final VehiclesBatchPublisher publisher;

    public VehiclePositionsListener(VehiclesBatchPublisher publisher) {
        this.publisher = publisher;
    }

    @EventListener
    public void onBatch(MicroBatchCommitted event) {
        if (event.source() != EtlSource.GTFS_RT_VEHICLE_POSITION
                || event.result().positions().isEmpty()) {
            return;
        }
        List<VehiclePosition> positions = event.result().positions().stream()
                .map(VehiclePositionsListener::position)
                .toList();
        publisher.add(positions, event.minRecordTs(), event.committedAt());
    }

    static VehiclePosition position(VehiclePositionRow row) {
        return new VehiclePosition(
                row.routeId(),
                row.vehicleId(),
                row.tripId(),
                row.directionId(),
                row.lat(),
                row.lon(),
                row.bearing(),
                row.speedMps(),
                row.currentStatus(),
                row.stopId(),
                row.currentStopSequence(),
                row.occupancyStatus(),
                row.eventTimestamp());
    }
}
