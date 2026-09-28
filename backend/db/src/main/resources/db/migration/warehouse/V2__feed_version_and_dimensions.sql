-- Feed versions and dimensions (DR-10, ADR-0009).

CREATE TABLE dw.gtfs_feed_version (
  feed_version_id        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  feed_hash              CHAR(64)    NOT NULL CONSTRAINT gtfs_feed_version_hash_uk UNIQUE
                                     CHECK (feed_hash ~ '^[0-9a-f]{64}$'),
  source_uri             TEXT        NOT NULL,
  raw_object_key         TEXT        NOT NULL,              -- raw/gtfs-static/<feed_hash>.zip
  publisher_name         TEXT        NULL,                  -- feed_info.feed_publisher_name
  publisher_feed_version TEXT        NULL,                  -- feed_info.feed_version
  agency_timezone        TEXT        NOT NULL,              -- single timezone for the whole feed
  valid_from             DATE        NULL,                  -- min service date (calendar + calendar_dates)
  valid_to               DATE        NULL,                  -- max service date
  bbox_min_lon           DOUBLE PRECISION NULL,             -- stop bounding box, used by the bbox DQ rule
  bbox_min_lat           DOUBLE PRECISION NULL,
  bbox_max_lon           DOUBLE PRECISION NULL,
  bbox_max_lat           DOUBLE PRECISION NULL,
  status                 TEXT        NOT NULL DEFAULT 'STAGED'
                                     CHECK (status IN ('STAGED', 'ACTIVE', 'RETIRED', 'REJECTED')),
  loaded_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
  activated_at           TIMESTAMPTZ NULL,
  retired_at             TIMESTAMPTZ NULL,
  validation_report      JSONB       NULL,
  job_execution_id       BIGINT      NULL,                  -- batch.batch_job_execution that loaded it
  CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to),
  CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR (activated_at IS NOT NULL AND valid_from IS NOT NULL
                                                  AND bbox_min_lon IS NOT NULL)),
  CHECK (status <> 'RETIRED' OR retired_at IS NOT NULL)
);
-- At most one ACTIVE version. The swap retires the old version first, then activates the new one.
CREATE UNIQUE INDEX gtfs_feed_version_one_active ON dw.gtfs_feed_version ((true)) WHERE status = 'ACTIVE';

CREATE TABLE dw.dim_agency (
  feed_version_id BIGINT NOT NULL REFERENCES dw.gtfs_feed_version (feed_version_id),
  agency_id       TEXT   NOT NULL,
  agency_name     TEXT   NOT NULL,
  agency_url      TEXT   NULL,
  agency_timezone TEXT   NOT NULL,
  agency_lang     TEXT   NULL,
  agency_phone    TEXT   NULL,
  agency_fare_url TEXT   NULL,
  PRIMARY KEY (feed_version_id, agency_id)
);

CREATE TABLE dw.dim_route (
  feed_version_id         BIGINT   NOT NULL,
  route_id                TEXT     NOT NULL,
  agency_id               TEXT     NOT NULL,
  route_short_name        TEXT     NULL,
  route_long_name         TEXT     NULL,
  route_desc              TEXT     NULL,
  display_name            TEXT     NOT NULL,   -- short name, else long name, else route_id
  route_type              SMALLINT NOT NULL CHECK (route_type BETWEEN 0 AND 12 OR route_type BETWEEN 100 AND 1702),
  route_color             CHAR(6)  NULL CHECK (route_color ~ '^[0-9A-F]{6}$'),
  route_text_color        CHAR(6)  NULL CHECK (route_text_color ~ '^[0-9A-F]{6}$'),
  route_sort_order        INT      NULL,
  typical_headway_seconds INT      NULL,       -- display only (DR-12); weekday 07-19 median
  PRIMARY KEY (feed_version_id, route_id),
  FOREIGN KEY (feed_version_id, agency_id) REFERENCES dw.dim_agency (feed_version_id, agency_id)
);

CREATE TABLE dw.dim_stop (
  feed_version_id     BIGINT           NOT NULL REFERENCES dw.gtfs_feed_version (feed_version_id),
  stop_id             TEXT             NOT NULL,
  stop_code           TEXT             NULL,
  stop_name           TEXT             NOT NULL,
  stop_desc           TEXT             NULL,
  lat                 DOUBLE PRECISION NULL CHECK (lat BETWEEN -90 AND 90),
  lon                 DOUBLE PRECISION NULL CHECK (lon BETWEEN -180 AND 180),
  location_type       SMALLINT         NOT NULL DEFAULT 0 CHECK (location_type BETWEEN 0 AND 4),
  parent_station      TEXT             NULL,
  wheelchair_boarding SMALLINT         NOT NULL DEFAULT 0 CHECK (wheelchair_boarding BETWEEN 0 AND 2),
  platform_code       TEXT             NULL,
  PRIMARY KEY (feed_version_id, stop_id),
  -- GTFS: stops, stations and entrances need coordinates; generic nodes and boarding areas may omit them.
  CHECK (location_type IN (3, 4) OR (lat IS NOT NULL AND lon IS NOT NULL))
);

-- Not versioned: fed by vehicles.txt and by realtime data (FR-01.6).
CREATE TABLE dw.dim_vehicle (
  vehicle_id          TEXT        PRIMARY KEY,
  vehicle_label       TEXT        NULL,
  vehicle_model       TEXT        NULL,    -- vehicles.txt vehicle_description, e.g. 40LFBUS
  seated_capacity     INT         NULL CHECK (seated_capacity >= 0),
  standing_capacity   INT         NULL CHECK (standing_capacity >= 0),
  capacity            INT         GENERATED ALWAYS AS (seated_capacity + standing_capacity) STORED,
  low_floor           BOOLEAN     NULL,
  wheelchair_access   TEXT        NULL,
  fuel                TEXT        NULL,
  source              TEXT        NOT NULL CHECK (source IN ('FEED', 'REALTIME')),
  first_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Not versioned: fed by CDC (ticketing.sale_points.cdc); INFERRED rows are placeholders
-- created when a sale arrives before its sale point (DOC-09 §5.3).
CREATE TABLE dw.dim_sale_point (
  sale_point_id TEXT        PRIMARY KEY,
  name          TEXT        NULL,
  kind          TEXT        NULL CHECK (kind IN ('KIOSK', 'ONBOARD', 'APP')),
  stop_id       TEXT        NULL,
  route_id      TEXT        NULL,
  source        TEXT        NOT NULL CHECK (source IN ('CDC', 'INFERRED')),
  is_deleted    BOOLEAN     NOT NULL DEFAULT false,
  source_lsn    BIGINT      NULL,
  batch_id      UUID        NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (source = 'INFERRED' OR (name IS NOT NULL AND kind IS NOT NULL AND source_lsn IS NOT NULL))
);

CREATE VIEW dw.dim_agency_current AS
  SELECT a.* FROM dw.dim_agency a
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = a.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.dim_route_current AS
  SELECT r.* FROM dw.dim_route r
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = r.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.dim_stop_current AS
  SELECT s.* FROM dw.dim_stop s
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = s.feed_version_id AND f.status = 'ACTIVE';
