-- name: insert_dead_letter_live (DOC-22 §1.3). A redelivered record keeps its first dead letter.
INSERT INTO ops.dead_letter (
  id, source, stage, error_class, error_message, rule_id, raw_payload,
  kafka_topic, kafka_partition, kafka_offset, kafka_timestamp, business_key, batch_id)
VALUES (
  :id, :source, :stage, :error_class, :error_message, :rule_id, :raw_payload,
  :kafka_topic, :kafka_partition, :kafka_offset, :kafka_timestamp, :business_key, :batch_id)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) WHERE kafka_topic IS NOT NULL DO NOTHING
