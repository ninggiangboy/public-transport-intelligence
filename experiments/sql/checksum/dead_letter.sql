-- ops.dead_letter as a set of Kafka positions (DR-58, DOC-45 §4.5): every column but the position, source,
-- stage and rule is excluded. Parameters :from and :to bound created_at; run with TimeZone = UTC.
SELECT count(*) AS row_count,
       md5(coalesce(string_agg(concat_ws('|', kafka_topic, kafka_partition::text, kafka_offset::text, source, stage,
                                         coalesce(rule_id, '∅')),
                               E'\n' ORDER BY kafka_topic, kafka_partition, kafka_offset), '')) AS checksum
FROM ops.dead_letter
WHERE created_at >= :from AND created_at < :to;
