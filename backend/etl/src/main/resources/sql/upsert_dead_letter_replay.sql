-- name: upsert_dead_letter_replay (DOC-22 §1.3). Replay refreshes the existing dead letter with the latest failure.
WITH old AS (
  SELECT id, stage, error_class FROM ops.dead_letter
  WHERE kafka_topic = :kafka_topic AND kafka_partition = :kafka_partition AND kafka_offset = :kafka_offset
)
INSERT INTO ops.dead_letter AS d (
  id, source, stage, error_class, error_message, rule_id, raw_payload,
  kafka_topic, kafka_partition, kafka_offset, kafka_timestamp, business_key, batch_id)
VALUES (
  :id, :source, :stage, :error_class, :error_message, :rule_id, :raw_payload,
  :kafka_topic, :kafka_partition, :kafka_offset, :kafka_timestamp, :business_key, :batch_id)
ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) WHERE kafka_topic IS NOT NULL DO UPDATE SET
  stage = excluded.stage, error_class = excluded.error_class, error_message = excluded.error_message,
  rule_id = excluded.rule_id, batch_id = excluded.batch_id,
  replay_count = d.replay_count + 1, last_replay_at = now(), updated_at = now(),
  status = CASE WHEN d.status IN ('DISCARDED', 'RESOLVED') THEN d.status ELSE 'NEW' END,
  triage_lease_until = CASE WHEN d.status IN ('DISCARDED', 'RESOLVED') THEN d.triage_lease_until ELSE NULL END
RETURNING d.id, (xmax = 0) AS inserted, (SELECT stage FROM old) AS old_stage,
          (SELECT error_class FROM old) AS old_error_class
