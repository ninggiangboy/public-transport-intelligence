-- Facts (partitioned, ADR-0011), latest positions (DR-14), partition maintenance, baseline shadow tables (DR-27).

-- Grain: one reported position of one vehicle at one event time.
CREATE TABLE dw.fact_vehicle_position (
  service_date          DATE             NOT NULL,   -- payload.start_date
  vehicle_id            TEXT             NOT NULL,
  event_timestamp       TIMESTAMPTZ      NOT NULL,
  trip_id               TEXT             NOT NULL,
  route_id              TEXT             NOT NULL,
  direction_id          SMALLINT         NOT NULL CHECK (direction_id IN (0, 1)),
  lat                   DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lon                   DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180),
  bearing               REAL             NULL CHECK (bearing >= 0 AND bearing <= 360),
  speed_mps             REAL             NULL CHECK (speed_mps >= 0),
  current_stop_sequence INT              NOT NULL CHECK (current_stop_sequence >= 0),
  stop_id               TEXT             NOT NULL,
  current_status        TEXT             NOT NULL CHECK (current_status IN ('INCOMING_AT', 'STOPPED_AT', 'IN_TRANSIT_TO')),
  occupancy_status      TEXT             NULL,       -- schema_version >= 2
  schema_version        SMALLINT         NOT NULL,
  payload_hash          CHAR(64)         NOT NULL,
  batch_id              UUID             NOT NULL,
  ingested_at           TIMESTAMPTZ      NOT NULL DEFAULT now(),
  updated_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
  CONSTRAINT fact_vehicle_position_pk PRIMARY KEY (service_date, vehicle_id, event_timestamp)
) PARTITION BY RANGE (service_date);
CREATE INDEX fact_vehicle_position_route_idx ON dw.fact_vehicle_position (service_date, route_id, event_timestamp);
CREATE INDEX fact_vehicle_position_batch_idx ON dw.fact_vehicle_position (batch_id);

-- Grain: one stop of one trip on one service date; the latest known state (DR-13).
CREATE TABLE dw.fact_trip_update (
  service_date            DATE        NOT NULL,   -- payload.start_date
  trip_id                 TEXT        NOT NULL,
  stop_sequence           INT         NOT NULL CHECK (stop_sequence >= 0),
  route_id                TEXT        NOT NULL,
  direction_id            SMALLINT    NOT NULL CHECK (direction_id IN (0, 1)),
  stop_id                 TEXT        NOT NULL,
  vehicle_id              TEXT        NOT NULL,
  schedule_relationship   TEXT        NOT NULL CHECK (schedule_relationship IN ('SCHEDULED', 'SKIPPED', 'NO_DATA')),
  scheduled_arrival       TIMESTAMPTZ NULL,       -- arrival.time - arrival.delay (else departure)
  arrival_time            TIMESTAMPTZ NULL,
  departure_time          TIMESTAMPTZ NULL,
  delay_seconds           INT         NULL CHECK (delay_seconds BETWEEN -86400 AND 86400),
  is_observed             BOOLEAN     NOT NULL,   -- coalesce(arrival, departure) <= event_timestamp
  event_timestamp         TIMESTAMPTZ NOT NULL,
  payload_hash            CHAR(64)    NOT NULL,
  batch_id                UUID        NOT NULL,
  ingested_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fact_trip_update_pk PRIMARY KEY (service_date, trip_id, stop_sequence),
  CHECK (schedule_relationship <> 'SCHEDULED' OR arrival_time IS NOT NULL OR departure_time IS NOT NULL)
) PARTITION BY RANGE (service_date);
-- Only immutable columns are indexed, so prediction updates stay HOT (DR-65). No index on batch_id
-- for the same reason: lineage queries on this table filter by service_date and route_id first.
CREATE INDEX fact_trip_update_route_idx ON dw.fact_trip_update (service_date, route_id);
CREATE INDEX fact_trip_update_stop_idx  ON dw.fact_trip_update (service_date, stop_id);

-- Grain: one ticketing transaction (sale or refund). Key includes sale_date because the table is
-- partitioned by it; sale_date = created_at in the agency timezone.
CREATE TABLE dw.fact_ticket_sales (
  sale_date         DATE          NOT NULL,
  transaction_id    UUID          NOT NULL,
  sale_point_id     TEXT          NOT NULL,
  route_id          TEXT          NULL,
  stop_id           TEXT          NULL,
  ticket_type       TEXT          NOT NULL CHECK (ticket_type IN ('SINGLE', 'DAY', 'MONTH')),
  txn_type          TEXT          NOT NULL CHECK (txn_type IN ('SALE', 'REFUND')),
  amount            NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  currency          CHAR(3)       NOT NULL,
  refund_of         UUID          NULL,
  status            TEXT          NOT NULL CHECK (status IN ('COMPLETED', 'VOIDED')),
  is_deleted        BOOLEAN       NOT NULL DEFAULT false,
  created_at        TIMESTAMPTZ   NOT NULL,       -- source created_at
  source_updated_at TIMESTAMPTZ   NOT NULL,       -- source updated_at
  source_lsn        BIGINT        NOT NULL,       -- __lsn, ordering guard (FR-03.2)
  event_timestamp   TIMESTAMPTZ   NOT NULL,       -- __source_ts_ms, commit time at the source
  payload_hash      CHAR(64)      NOT NULL,
  batch_id          UUID          NOT NULL,
  ingested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT fact_ticket_sales_pk PRIMARY KEY (sale_date, transaction_id),
  CHECK ((txn_type = 'REFUND') = (refund_of IS NOT NULL))
) PARTITION BY RANGE (sale_date);
CREATE INDEX fact_ticket_sales_sale_point_idx ON dw.fact_ticket_sales (sale_point_id, created_at);
CREATE INDEX fact_ticket_sales_txn_idx        ON dw.fact_ticket_sales (transaction_id);  -- refund lookups
CREATE INDEX fact_ticket_sales_batch_idx      ON dw.fact_ticket_sales (batch_id);

CREATE TABLE dw.fact_vehicle_position_default PARTITION OF dw.fact_vehicle_position DEFAULT;
CREATE TABLE dw.fact_trip_update_default      PARTITION OF dw.fact_trip_update DEFAULT WITH (fillfactor = 80, autovacuum_vacuum_scale_factor = 0.02);
CREATE TABLE dw.fact_ticket_sales_default     PARTITION OF dw.fact_ticket_sales DEFAULT;

-- One row per vehicle, the newest position (DR-14). Serves GET /vehicles/live.
CREATE TABLE dw.vehicle_position_latest (
  vehicle_id            TEXT             PRIMARY KEY,
  service_date          DATE             NOT NULL,
  route_id              TEXT             NOT NULL,
  trip_id               TEXT             NOT NULL,
  direction_id          SMALLINT         NOT NULL,
  lat                   DOUBLE PRECISION NOT NULL,
  lon                   DOUBLE PRECISION NOT NULL,
  bearing               REAL             NULL,
  speed_mps             REAL             NULL,
  current_stop_sequence INT              NOT NULL,
  stop_id               TEXT             NOT NULL,
  current_status        TEXT             NOT NULL,
  occupancy_status      TEXT             NULL,
  event_timestamp       TIMESTAMPTZ      NOT NULL,
  batch_id              UUID             NOT NULL,
  updated_at            TIMESTAMPTZ      NOT NULL DEFAULT now()
) WITH (fillfactor = 50, autovacuum_vacuum_scale_factor = 0.01);   -- ~1,100 rows, each rewritten every 5 s
CREATE INDEX vehicle_position_latest_route_idx ON dw.vehicle_position_latest (route_id);

-- Partition maintenance (ADR-0011). SECURITY DEFINER so that etl_writer needs EXECUTE only.
CREATE FUNCTION dw.partition_spec(p_table TEXT, OUT grain TEXT, OUT key_column TEXT, OUT storage TEXT)
LANGUAGE sql IMMUTABLE
SET search_path = pg_catalog
AS $$
  SELECT s.grain, s.key_column, s.storage
  FROM (VALUES ('fact_vehicle_position', 'day',   'service_date', ''),
               ('fact_trip_update',      'day',   'service_date', ' WITH (fillfactor = 80, autovacuum_vacuum_scale_factor = 0.02)'),
               ('fact_ticket_sales',     'month', 'sale_date',    '')) AS s(tbl, grain, key_column, storage)
  WHERE s.tbl = p_table
$$;

-- Creates the missing partitions covering [p_from, p_to]. Rows already sitting in the DEFAULT
-- partition for a new range are moved into it. Returns the number of partitions created.
CREATE FUNCTION dw.ensure_partitions(p_table TEXT, p_from DATE, p_to DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
  v_spec    RECORD;
  v_start   DATE;
  v_end     DATE;
  v_name    TEXT;
  v_moved   BIGINT;
  v_created INT := 0;
BEGIN
  SELECT * INTO v_spec FROM dw.partition_spec(p_table);
  IF v_spec.grain IS NULL THEN
    RAISE EXCEPTION 'table % is not partition-managed', p_table USING ERRCODE = '22023';
  END IF;
  IF p_to < p_from OR p_to - p_from > 400 THEN
    RAISE EXCEPTION 'invalid range % .. %', p_from, p_to USING ERRCODE = '22023';
  END IF;

  v_start := CASE v_spec.grain WHEN 'day' THEN p_from ELSE date_trunc('month', p_from)::date END;
  WHILE v_start <= p_to LOOP
    v_end  := CASE v_spec.grain WHEN 'day' THEN v_start + 1 ELSE (v_start + interval '1 month')::date END;
    v_name := p_table || '_p' || to_char(v_start, CASE v_spec.grain WHEN 'day' THEN 'YYYYMMDD' ELSE 'YYYYMM' END);

    IF to_regclass(format('dw.%I', v_name)) IS NULL THEN
      EXECUTE format('SELECT count(*) FROM dw.%I WHERE %I >= %L AND %I < %L',
                     p_table || '_default', v_spec.key_column, v_start, v_spec.key_column, v_end)
        INTO v_moved;
      IF v_moved = 0 THEN
        EXECUTE format('CREATE TABLE dw.%I PARTITION OF dw.%I FOR VALUES FROM (%L) TO (%L)%s',
                       v_name, p_table, v_start, v_end, v_spec.storage);
      ELSE
        -- A partition cannot be created while DEFAULT holds rows of its range: build it detached,
        -- move the rows, then attach.
        EXECUTE format('CREATE TABLE dw.%I (LIKE dw.%I INCLUDING DEFAULTS INCLUDING CONSTRAINTS)%s',
                       v_name, p_table, v_spec.storage);
        EXECUTE format('WITH moved AS (DELETE FROM dw.%I WHERE %I >= %L AND %I < %L RETURNING *) '
                       'INSERT INTO dw.%I SELECT * FROM moved',
                       p_table || '_default', v_spec.key_column, v_start, v_spec.key_column, v_end, v_name);
        EXECUTE format('ALTER TABLE dw.%I ATTACH PARTITION dw.%I FOR VALUES FROM (%L) TO (%L)',
                       p_table, v_name, v_start, v_end);
        RAISE NOTICE 'moved % rows from %_default into %', v_moved, p_table, v_name;
      END IF;
      v_created := v_created + 1;
    END IF;
    v_start := v_end;
  END LOOP;
  RETURN v_created;
END $$;

-- Detaches and drops partitions whose whole range is before p_cutoff, and deletes rows before
-- p_cutoff from DEFAULT. Returns the number of partitions dropped.
CREATE FUNCTION dw.drop_partitions_before(p_table TEXT, p_cutoff DATE) RETURNS INT
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
SET lock_timeout = '5s'
AS $$
DECLARE
  v_spec    RECORD;
  v_part    RECORD;
  v_end     DATE;
  v_dropped INT := 0;
BEGIN
  SELECT * INTO v_spec FROM dw.partition_spec(p_table);
  IF v_spec.grain IS NULL THEN
    RAISE EXCEPTION 'table % is not partition-managed', p_table USING ERRCODE = '22023';
  END IF;

  FOR v_part IN
    SELECT c.relname
    FROM pg_inherits i
    JOIN pg_class c ON c.oid = i.inhrelid
    WHERE i.inhparent = format('dw.%I', p_table)::regclass
      AND c.relname ~ ('^' || p_table || '_p[0-9]{6}([0-9]{2})?$')
    ORDER BY c.relname
  LOOP
    v_end := CASE v_spec.grain
               WHEN 'day'   THEN to_date(right(v_part.relname, 8), 'YYYYMMDD') + 1
               ELSE (to_date(right(v_part.relname, 6), 'YYYYMM') + interval '1 month')::date
             END;
    IF v_end <= p_cutoff THEN
      EXECUTE format('ALTER TABLE dw.%I DETACH PARTITION dw.%I', p_table, v_part.relname);
      EXECUTE format('DROP TABLE dw.%I', v_part.relname);
      v_dropped := v_dropped + 1;
    END IF;
  END LOOP;

  EXECUTE format('DELETE FROM dw.%I WHERE %I < %L', p_table || '_default', v_spec.key_column, p_cutoff);
  RETURN v_dropped;
END $$;

REVOKE ALL ON FUNCTION dw.ensure_partitions(TEXT, DATE, DATE) FROM PUBLIC;
REVOKE ALL ON FUNCTION dw.drop_partitions_before(TEXT, DATE)  FROM PUBLIC;

-- Initial partitions so the system works before PartitionMaintenanceJob has ever run.
SELECT dw.ensure_partitions('fact_vehicle_position', current_date - 3, current_date + 7);
SELECT dw.ensure_partitions('fact_trip_update',      current_date - 3, current_date + 7);
SELECT dw.ensure_partitions('fact_ticket_sales',     current_date - 3, current_date + 40);

-- Baseline shadow tables (DR-27): same columns, no keys, so duplicates can be counted.
-- Any column change to a dw.fact_* table must be applied here in the same migration.
CREATE TABLE exp.exp_fact_vehicle_position (LIKE dw.fact_vehicle_position INCLUDING DEFAULTS);
CREATE TABLE exp.exp_fact_trip_update      (LIKE dw.fact_trip_update      INCLUDING DEFAULTS);
CREATE TABLE exp.exp_fact_ticket_sales     (LIKE dw.fact_ticket_sales     INCLUDING DEFAULTS);
