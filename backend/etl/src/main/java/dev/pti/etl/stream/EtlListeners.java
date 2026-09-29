package dev.pti.etl.stream;

import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * The Kafka entry points of {@code etl-stream} (DOC-20 §1, §3). Returning normally lets {@code AckMode.BATCH}
 * commit the offsets of the poll; an exception leaves them uncommitted (ADR-0004).
 */
public class EtlListeners {

    private final StreamChunkHandler handler;

    public EtlListeners(StreamChunkHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            id = "gtfs-rt-vehicle-position",
            topics = "#{@topicNames.named('GTFS_RT_VEHICLE_POSITION')}",
            groupId = "pti-etl-gtfs-rt",
            clientIdPrefix = "${HOSTNAME:etl}-gtfs-rt-vehicle-position",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.gtfs-rt-vehicle-position.concurrency:3}",
            autoStartup = "false")
    void onVehiclePositions(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.GTFS_RT_VEHICLE_POSITION, records);
    }

    @KafkaListener(
            id = "gtfs-rt-trip-update",
            topics = "#{@topicNames.named('GTFS_RT_TRIP_UPDATE')}",
            groupId = "pti-etl-gtfs-rt",
            clientIdPrefix = "${HOSTNAME:etl}-gtfs-rt-trip-update",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.gtfs-rt-trip-update.concurrency:3}",
            autoStartup = "false")
    void onTripUpdates(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.GTFS_RT_TRIP_UPDATE, records);
    }

    @KafkaListener(
            id = "ticketing-sales",
            topics = "#{@topicNames.named('TICKETING_SALES')}",
            groupId = "pti-etl-ticketing",
            clientIdPrefix = "${HOSTNAME:etl}-ticketing-sales",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.ticketing-sales.concurrency:2}")
    void onSales(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.TICKETING_SALES, records);
    }

    @KafkaListener(
            id = "ticketing-sale-points",
            topics = "#{@topicNames.named('TICKETING_SALE_POINTS')}",
            groupId = "pti-etl-ticketing",
            clientIdPrefix = "${HOSTNAME:etl}-ticketing-sale-points",
            containerFactory = "etlBatchListenerFactory",
            concurrency = "${pti.etl.listener.ticketing-sale-points.concurrency:1}")
    void onSalePoints(List<ConsumerRecord<String, byte[]>> records) {
        handler.handle(StreamListener.TICKETING_SALE_POINTS, records);
    }
}
