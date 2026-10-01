-- E-42 and the read-back of E-43, E-46, E-47: one dead letter with both payloads.
SELECT id, source, stage, rule_id, error_class, error_message, status, category, category_confidence, severity,
       severity_confidence, business_key, edited_payload IS NOT NULL AS has_edited_payload, replay_count,
       auto_replay_count, created_at, updated_at,
       raw_payload, CAST(edited_payload AS text) AS edited_payload, kafka_topic, kafka_partition, kafka_offset,
       kafka_timestamp, batch_id, model_version, triaged_at, triage_attempts, last_replay_at, resolved_by, resolved_at
FROM ops.dead_letter
WHERE id = CAST(:id AS uuid)
