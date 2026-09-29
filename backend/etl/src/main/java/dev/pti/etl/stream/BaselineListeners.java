package dev.pti.etl.stream;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * The baseline listeners of DR-27 ({@code experiment} profile, DOC-20 §9): GTFS-realtime only, in their own consumer
 * group, so the baseline reads the same input as {@code etl-stream} and both can be measured in one run.
 */
public class BaselineListeners {

    private final StreamChunkHandler handler;

    public BaselineListeners(StreamChunkHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            id = "gtfs-rt-vehicle-position-baseline",
            topics = "#{@topicNames.named('GTFS_RT_VEHICLE_POSITION')}",
            groupId = "pti-exp-baseline",
            clientIdPrefix = "${HOSTNAME:etl}-gtfs-rt-vehicle-position-baseline",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.gtfs-rt-vehicle-position.concurrency:3}",
            autoStartup = "false")
    void onVehiclePositions(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.GTFS_RT_VEHICLE_POSITION, records);
    }

    @KafkaListener(
            id = "gtfs-rt-trip-update-baseline",
            topics = "#{@topicNames.named('GTFS_RT_TRIP_UPDATE')}",
            groupId = "pti-exp-baseline",
            clientIdPrefix = "${HOSTNAME:etl}-gtfs-rt-trip-update-baseline",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.gtfs-rt-trip-update.concurrency:3}",
            autoStartup = "false")
    void onTripUpdates(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.GTFS_RT_TRIP_UPDATE, records);
    }
}
