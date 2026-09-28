-- Versioned GTFS schedule tables, headway, and time helpers (DR-09, DR-10, DR-12).

CREATE TABLE dw.gtfs_calendar (
  feed_version_id BIGINT  NOT NULL REFERENCES dw.gtfs_feed_version (feed_version_id),
  service_id      TEXT    NOT NULL,
  monday          BOOLEAN NOT NULL,
  tuesday         BOOLEAN NOT NULL,
  wednesday       BOOLEAN NOT NULL,
  thursday        BOOLEAN NOT NULL,
  friday          BOOLEAN NOT NULL,
  saturday        BOOLEAN NOT NULL,
  sunday          BOOLEAN NOT NULL,
  start_date      DATE    NOT NULL,
  end_date        DATE    NOT NULL,
  PRIMARY KEY (feed_version_id, service_id),
  CHECK (start_date <= end_date)
);

CREATE TABLE dw.gtfs_calendar_date (
  feed_version_id BIGINT   NOT NULL REFERENCES dw.gtfs_feed_version (feed_version_id),
  service_id      TEXT     NOT NULL,
  date            DATE     NOT NULL,
  exception_type  SMALLINT NOT NULL CHECK (exception_type IN (1, 2)),  -- 1 added, 2 removed
  PRIMARY KEY (feed_version_id, service_id, date)
);

CREATE TABLE dw.gtfs_trip (
  feed_version_id       BIGINT   NOT NULL,
  trip_id               TEXT     NOT NULL,
  route_id              TEXT     NOT NULL,
  service_id            TEXT     NOT NULL,
  direction_id          SMALLINT NOT NULL CHECK (direction_id IN (0, 1)),
  direction_label       TEXT     NULL,       -- non-standard trips.direction (NB/SB/EB/WB)
  trip_headsign         TEXT     NULL,
  block_id              TEXT     NULL,
  shape_id              TEXT     NULL,
  wheelchair_accessible SMALLINT NOT NULL DEFAULT 0 CHECK (wheelchair_accessible BETWEEN 0 AND 2),
  PRIMARY KEY (feed_version_id, trip_id),
  FOREIGN KEY (feed_version_id, route_id) REFERENCES dw.dim_route (feed_version_id, route_id)
);
CREATE INDEX gtfs_trip_route_idx ON dw.gtfs_trip (feed_version_id, route_id, direction_id);
CREATE INDEX gtfs_trip_block_idx ON dw.gtfs_trip (feed_version_id, block_id);

-- Times are seconds after "noon minus 12h" of the service date, so 25:10:00 = 90600 (DR-09).
CREATE TABLE dw.gtfs_stop_time (
  feed_version_id     BIGINT           NOT NULL,
  trip_id             TEXT             NOT NULL,
  stop_sequence       INT              NOT NULL CHECK (stop_sequence >= 0),
  stop_id             TEXT             NOT NULL,
  arrival_seconds     INT              NOT NULL CHECK (arrival_seconds >= 0),
  departure_seconds   INT              NOT NULL CHECK (departure_seconds >= 0),
  pickup_type         SMALLINT         NOT NULL DEFAULT 0 CHECK (pickup_type BETWEEN 0 AND 3),
  drop_off_type       SMALLINT         NOT NULL DEFAULT 0 CHECK (drop_off_type BETWEEN 0 AND 3),
  timepoint           BOOLEAN          NOT NULL DEFAULT true,
  shape_dist_traveled DOUBLE PRECISION NULL CHECK (shape_dist_traveled >= 0),
  PRIMARY KEY (feed_version_id, trip_id, stop_sequence),
  FOREIGN KEY (feed_version_id, trip_id) REFERENCES dw.gtfs_trip (feed_version_id, trip_id),
  FOREIGN KEY (feed_version_id, stop_id) REFERENCES dw.dim_stop (feed_version_id, stop_id),
  CHECK (departure_seconds >= arrival_seconds)
);
-- Stop departures board (GET /stops/{id}/arrivals) and scheduled headway computation.
CREATE INDEX gtfs_stop_time_stop_idx ON dw.gtfs_stop_time (feed_version_id, stop_id, departure_seconds);

CREATE TABLE dw.gtfs_shape (
  feed_version_id     BIGINT           NOT NULL REFERENCES dw.gtfs_feed_version (feed_version_id),
  shape_id            TEXT             NOT NULL,
  shape_pt_sequence   INT              NOT NULL CHECK (shape_pt_sequence >= 0),
  lat                 DOUBLE PRECISION NOT NULL CHECK (lat BETWEEN -90 AND 90),
  lon                 DOUBLE PRECISION NOT NULL CHECK (lon BETWEEN -180 AND 180),
  shape_dist_traveled DOUBLE PRECISION NULL CHECK (shape_dist_traveled >= 0),
  PRIMARY KEY (feed_version_id, shape_id, shape_pt_sequence)
);

CREATE TABLE dw.route_headway (
  feed_version_id           BIGINT   NOT NULL,
  route_id                  TEXT     NOT NULL,
  direction_id              SMALLINT NOT NULL CHECK (direction_id IN (0, 1)),
  day_type                  TEXT     NOT NULL CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY_HOLIDAY')),
  hour_of_day               SMALLINT NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),
  scheduled_headway_seconds INT      NULL CHECK (scheduled_headway_seconds > 0),  -- NULL when trip_count < 2
  trip_count                INT      NOT NULL CHECK (trip_count >= 1),
  PRIMARY KEY (feed_version_id, route_id, direction_id, day_type, hour_of_day),
  FOREIGN KEY (feed_version_id, route_id) REFERENCES dw.dim_route (feed_version_id, route_id)
);

CREATE VIEW dw.gtfs_calendar_current AS
  SELECT c.* FROM dw.gtfs_calendar c
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = c.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.gtfs_calendar_date_current AS
  SELECT c.* FROM dw.gtfs_calendar_date c
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = c.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.gtfs_trip_current AS
  SELECT t.* FROM dw.gtfs_trip t
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = t.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.gtfs_stop_time_current AS
  SELECT st.* FROM dw.gtfs_stop_time st
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = st.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.gtfs_shape_current AS
  SELECT s.* FROM dw.gtfs_shape s
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = s.feed_version_id AND f.status = 'ACTIVE';
CREATE VIEW dw.route_headway_current AS
  SELECT h.* FROM dw.route_headway h
  JOIN dw.gtfs_feed_version f ON f.feed_version_id = h.feed_version_id AND f.status = 'ACTIVE';

-- GTFS time -> instant. The reference point is noon minus 12h local time, which differs from
-- local midnight on DST change days (DR-09). Java counterpart: GtfsTime.toInstant (common).
CREATE FUNCTION dw.gtfs_time_to_ts(p_service_date DATE, p_seconds INT, p_timezone TEXT)
RETURNS TIMESTAMPTZ
LANGUAGE sql STABLE PARALLEL SAFE
SET search_path = pg_catalog
AS $$
  SELECT ((p_service_date + time '12:00') AT TIME ZONE p_timezone)
         - interval '12 hours'
         + make_interval(secs => p_seconds)
$$;

-- Service ids running on a service date in a given feed version (calendar + calendar_dates).
-- Takes the version explicitly so GtfsStaticLoadJob can use it while the version is STAGED.
CREATE FUNCTION dw.service_ids_on(p_feed_version_id BIGINT, p_service_date DATE)
RETURNS SETOF TEXT
LANGUAGE sql STABLE
SET search_path = pg_catalog
AS $$
  SELECT c.service_id
  FROM dw.gtfs_calendar c
  WHERE c.feed_version_id = p_feed_version_id
    AND p_service_date BETWEEN c.start_date AND c.end_date
    AND CASE extract(isodow FROM p_service_date)::int
          WHEN 1 THEN c.monday   WHEN 2 THEN c.tuesday WHEN 3 THEN c.wednesday
          WHEN 4 THEN c.thursday WHEN 5 THEN c.friday  WHEN 6 THEN c.saturday
          ELSE c.sunday END
    AND NOT EXISTS (SELECT 1 FROM dw.gtfs_calendar_date d
                    WHERE d.feed_version_id = p_feed_version_id AND d.service_id = c.service_id
                      AND d.date = p_service_date AND d.exception_type = 2)
  UNION
  SELECT d.service_id
  FROM dw.gtfs_calendar_date d
  WHERE d.feed_version_id = p_feed_version_id AND d.date = p_service_date AND d.exception_type = 1
$$;
