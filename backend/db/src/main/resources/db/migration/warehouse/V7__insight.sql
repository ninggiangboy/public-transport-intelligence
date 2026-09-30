-- Analytics output and state (DR-29..DR-34), AI enrichment columns (DR-36, DR-37). Phase 4.
-- Every id is UUIDv5(namespace, natural key) computed in code, so a recomputation yields the same id.

CREATE DOMAIN insight.enrichment_status AS TEXT
  CHECK (VALUE IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED', 'SKIPPED'));

-- Bunching episodes (DR-29, DR-30). One row per (leader, follower) episode.
CREATE TABLE insight.insight_bus_bunching (
  id                        UUID        PRIMARY KEY,
  route_id                  TEXT        NOT NULL,
  direction_id              SMALLINT    NOT NULL CHECK (direction_id IN (0, 1)),
  vehicle_leader            TEXT        NOT NULL,
  vehicle_follower          TEXT        NOT NULL,
  trip_leader               TEXT        NOT NULL,
  trip_follower             TEXT        NOT NULL,
  episode_start             TIMESTAMPTZ NOT NULL,            -- event time of the first qualifying evaluation
  episode_end               TIMESTAMPTZ NULL,
  status                    TEXT        NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
  close_reason              TEXT        NULL CHECK (close_reason IN ('GAP_RECOVERED', 'PAIR_CHANGED', 'SIGNAL_LOST', 'OUT_OF_ZONE')),
  scheduled_headway_seconds INT         NOT NULL CHECK (scheduled_headway_seconds > 0),
  threshold_seconds         INT         NOT NULL CHECK (threshold_seconds > 0),
  min_gap_seconds           INT         NOT NULL,            -- peak: smallest gap seen in the episode
  last_gap_seconds          INT         NOT NULL,
  open_stop_id              TEXT        NULL,                -- follower's next stop when the episode opened
  evaluation_count          INT         NOT NULL DEFAULT 1 CHECK (evaluation_count >= 1),
  last_evaluated_at         TIMESTAMPTZ NOT NULL,            -- event time
  enrichment_status         insight.enrichment_status NOT NULL DEFAULT 'PENDING',  -- dispatch suggestion
  enrichment_attempts       SMALLINT    NOT NULL DEFAULT 0,
  enrichment_lease_until    TIMESTAMPTZ NULL,
  batch_id                  UUID        NOT NULL,
  created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT insight_bus_bunching_uk UNIQUE (route_id, vehicle_leader, vehicle_follower, episode_start),
  CHECK (vehicle_leader <> vehicle_follower),
  CHECK ((status = 'CLOSED') = (episode_end IS NOT NULL)),
  CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL)),
  CHECK (episode_end IS NULL OR episode_end >= episode_start)
);
CREATE INDEX insight_bus_bunching_route_idx ON insight.insight_bus_bunching (route_id, episode_start DESC);
CREATE INDEX insight_bus_bunching_open_idx  ON insight.insight_bus_bunching (route_id) WHERE status = 'OPEN';
CREATE INDEX insight_bus_bunching_enrich_idx ON insight.insight_bus_bunching (created_at)
  WHERE enrichment_status = 'PENDING';

-- Dispatch suggestions (SDD 9.5). No foreign key to the bunching row on purpose: an analytics
-- replay deletes and recomputes episodes, which get the same UUIDv5 id, and operator feedback
-- must survive that.
CREATE TABLE insight.insight_dispatch_suggestion (
  id                UUID         PRIMARY KEY,
  bunching_id       UUID         NOT NULL CONSTRAINT insight_dispatch_suggestion_bunching_uk UNIQUE,
  route_id          TEXT         NOT NULL,
  action            TEXT         NOT NULL CHECK (action IN ('hold_follower', 'skip_stops', 'no_action')),
  action_confidence NUMERIC(4,3) NOT NULL CHECK (action_confidence BETWEEN 0 AND 1),
  state_snapshot    JSONB        NOT NULL,   -- PII-free state sent to the model, for audit
  model_version     TEXT         NOT NULL,
  created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
  operator_feedback TEXT         NULL CHECK (operator_feedback IN ('accepted', 'ignored')),
  feedback_by       TEXT         NULL,
  feedback_at       TIMESTAMPTZ  NULL,
  CHECK ((operator_feedback IS NULL) = (feedback_by IS NULL) AND (feedback_by IS NULL) = (feedback_at IS NULL))
);
CREATE INDEX insight_dispatch_suggestion_route_idx ON insight.insight_dispatch_suggestion (route_id, created_at DESC);

-- Historical ETA per (route, stop, local day of week, local hour), recomputed hourly (DR-32).
CREATE TABLE insight.insight_eta_prediction (
  route_id             TEXT         NOT NULL,
  stop_id              TEXT         NOT NULL,
  day_of_week          SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
  hour_of_day          SMALLINT     NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),
  avg_delay_seconds    NUMERIC(8,1) NOT NULL,
  median_delay_seconds INT          NOT NULL,
  p90_delay_seconds    INT          NOT NULL,
  sample_count         INT          NOT NULL CHECK (sample_count > 0),
  window_start         DATE         NOT NULL,
  window_end           DATE         NOT NULL,
  computed_at          TIMESTAMPTZ  NOT NULL,
  batch_id             UUID         NOT NULL,
  PRIMARY KEY (route_id, stop_id, day_of_week, hour_of_day)
);
CREATE INDEX insight_eta_prediction_stop_idx ON insight.insight_eta_prediction (stop_id, day_of_week, hour_of_day);

-- Disruption episodes (DR-29, DR-31) with AI enrichment (SDD 9.4).
CREATE TABLE insight.insight_service_disruption (
  id                        UUID         PRIMARY KEY,
  route_id                  TEXT         NOT NULL,
  direction_id              SMALLINT     NOT NULL CHECK (direction_id IN (0, 1)),
  episode_start             TIMESTAMPTZ  NOT NULL,           -- start of the first qualifying bucket
  episode_end               TIMESTAMPTZ  NULL,
  status                    TEXT         NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
  close_reason              TEXT         NULL CHECK (close_reason IN ('RECOVERED', 'NO_DATA', 'MAX_DURATION')),
  baseline_mean_seconds     NUMERIC(8,1) NOT NULL,           -- baseline frozen while the episode is open
  baseline_stddev_seconds   NUMERIC(8,1) NOT NULL,
  current_avg_delay_seconds NUMERIC(8,1) NOT NULL,
  current_z_score           NUMERIC(6,2) NOT NULL,
  peak_avg_delay_seconds    NUMERIC(8,1) NOT NULL,
  peak_z_score              NUMERIC(6,2) NOT NULL,
  sample_count              INT          NOT NULL CHECK (sample_count >= 0),
  affected_stop_ids         TEXT[]       NOT NULL DEFAULT '{}',
  last_bucket               TIMESTAMPTZ  NOT NULL,
  data_issue_probability    NUMERIC(4,3) NULL CHECK (data_issue_probability BETWEEN 0 AND 1),
  likely_cause              TEXT         NULL CHECK (likely_cause IN (
                              'traffic', 'vehicle_breakdown', 'weather', 'event', 'data_issue', 'unknown')),
  cause_confidence          NUMERIC(4,3) NULL CHECK (cause_confidence BETWEEN 0 AND 1),
  model_version             TEXT         NULL,
  enriched_at               TIMESTAMPTZ  NULL,
  enrichment_status         insight.enrichment_status NOT NULL DEFAULT 'PENDING',
  enrichment_attempts       SMALLINT     NOT NULL DEFAULT 0,
  enrichment_lease_until    TIMESTAMPTZ  NULL,
  batch_id                  UUID         NOT NULL,
  created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT insight_service_disruption_uk UNIQUE (route_id, direction_id, episode_start),
  CHECK ((status = 'CLOSED') = (episode_end IS NOT NULL)),
  CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL)),
  CHECK (episode_end IS NULL OR episode_end >= episode_start),
  CHECK ((likely_cause IS NULL) = (cause_confidence IS NULL)),
  CHECK (enrichment_status <> 'DONE' OR (enriched_at IS NOT NULL AND model_version IS NOT NULL))
);
CREATE INDEX insight_service_disruption_route_idx ON insight.insight_service_disruption (route_id, episode_start DESC);
CREATE INDEX insight_service_disruption_open_idx  ON insight.insight_service_disruption (route_id) WHERE status = 'OPEN';
CREATE INDEX insight_service_disruption_enrich_idx ON insight.insight_service_disruption (created_at)
  WHERE enrichment_status = 'PENDING';

-- Daily on-time performance per route (DR-33).
CREATE TABLE insight.insight_otp_scorecard (
  route_id                TEXT         NOT NULL,
  service_date            DATE         NOT NULL,
  otp_percentage          NUMERIC(5,2) NOT NULL CHECK (otp_percentage BETWEEN 0 AND 100),
  on_time_count           INT          NOT NULL CHECK (on_time_count >= 0),
  early_count             INT          NOT NULL CHECK (early_count >= 0),
  late_count              INT          NOT NULL CHECK (late_count >= 0),
  observation_count       INT          NOT NULL CHECK (observation_count > 0),
  trip_count              INT          NOT NULL CHECK (trip_count > 0),
  early_tolerance_seconds INT          NOT NULL,
  late_tolerance_seconds  INT          NOT NULL,
  computed_at             TIMESTAMPTZ  NOT NULL,
  batch_id                UUID         NOT NULL,
  PRIMARY KEY (route_id, service_date),
  CHECK (on_time_count + early_count + late_count = observation_count)
);
CREATE INDEX insight_otp_scorecard_date_idx ON insight.insight_otp_scorecard (service_date);

-- Ticketing anomalies per sale point and 15-minute window (DR-34) with AI classification (SDD 9.3).
CREATE TABLE insight.insight_ticketing_anomaly (
  id                     UUID          PRIMARY KEY,
  sale_point_id          TEXT          NOT NULL,
  window_start           TIMESTAMPTZ   NOT NULL,
  window_end             TIMESTAMPTZ   NOT NULL,
  trigger                TEXT          NOT NULL CHECK (trigger IN ('VOLUME', 'REFUND_RATIO', 'BOTH')),
  txn_count              INT           NOT NULL CHECK (txn_count >= 0),
  refund_count           INT           NOT NULL CHECK (refund_count >= 0),
  refund_ratio           NUMERIC(5,4)  NOT NULL CHECK (refund_ratio BETWEEN 0 AND 1),
  amount_sum             NUMERIC(12,2) NOT NULL,
  baseline_mean          NUMERIC(10,2) NULL,
  baseline_stddev        NUMERIC(10,2) NULL,
  z_score                NUMERIC(6,2)  NULL,
  summary                JSONB         NOT NULL,       -- PII-free window summary, also the model state
  category               TEXT          NULL CHECK (category IN ('fraud_suspect', 'system_error', 'promo_spike', 'normal')),
  category_confidence    NUMERIC(4,3)  NULL CHECK (category_confidence BETWEEN 0 AND 1),
  severity               SMALLINT      NULL CHECK (severity BETWEEN 0 AND 2),
  severity_confidence    NUMERIC(4,3)  NULL CHECK (severity_confidence BETWEEN 0 AND 1),
  model_version          TEXT          NULL,
  enriched_at            TIMESTAMPTZ   NULL,
  enrichment_status      insight.enrichment_status NOT NULL DEFAULT 'PENDING',
  enrichment_attempts    SMALLINT      NOT NULL DEFAULT 0,
  enrichment_lease_until TIMESTAMPTZ   NULL,
  detected_at            TIMESTAMPTZ   NOT NULL,       -- event time = window_end
  batch_id               UUID          NOT NULL,
  created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CONSTRAINT insight_ticketing_anomaly_uk UNIQUE (sale_point_id, window_start),
  CHECK (window_end > window_start),
  CHECK ((category IS NULL) = (category_confidence IS NULL)),
  CHECK ((severity IS NULL) = (severity_confidence IS NULL))
);
CREATE INDEX insight_ticketing_anomaly_detected_idx ON insight.insight_ticketing_anomaly (detected_at DESC);
CREATE INDEX insight_ticketing_anomaly_enrich_idx ON insight.insight_ticketing_anomaly (created_at)
  WHERE enrichment_status = 'PENDING';

-- Disruption detector state per route and direction (DR-31).
CREATE TABLE insight.analytics_route_baseline (
  route_id          TEXT             NOT NULL,
  direction_id      SMALLINT         NOT NULL CHECK (direction_id IN (0, 1)),
  ewma_mean         DOUBLE PRECISION NOT NULL,
  ewma_var          DOUBLE PRECISION NOT NULL CHECK (ewma_var >= 0),
  bucket_count      INT              NOT NULL CHECK (bucket_count >= 0),  -- warm-up counter
  last_bucket       TIMESTAMPTZ      NOT NULL,
  consecutive_high  SMALLINT         NOT NULL DEFAULT 0,
  consecutive_low   SMALLINT         NOT NULL DEFAULT 0,
  open_episode_id   UUID             NULL,
  updated_at        TIMESTAMPTZ      NOT NULL DEFAULT now(),
  PRIMARY KEY (route_id, direction_id)
);

-- Hourly copies of the baseline, the starting point of an analytics replay (DR-31).
CREATE TABLE insight.analytics_baseline_snapshot (
  snapshot_hour    TIMESTAMPTZ      NOT NULL,   -- event-time hour boundary
  route_id         TEXT             NOT NULL,
  direction_id     SMALLINT         NOT NULL,
  ewma_mean        DOUBLE PRECISION NOT NULL,
  ewma_var         DOUBLE PRECISION NOT NULL,
  bucket_count     INT              NOT NULL,
  last_bucket      TIMESTAMPTZ      NOT NULL,
  consecutive_high SMALLINT         NOT NULL,
  consecutive_low  SMALLINT         NOT NULL,
  open_episode_id  UUID             NULL,
  PRIMARY KEY (snapshot_hour, route_id, direction_id),
  CHECK (date_trunc('hour', snapshot_hour AT TIME ZONE 'UTC') = snapshot_hour AT TIME ZONE 'UTC')
);

-- Bunching detector state per vehicle pair (DR-30: two consecutive evaluations to open,
-- hysteresis to close). Rows of pairs that are no longer adjacent are deleted by the 30 s tick.
CREATE TABLE insight.analytics_bunching_pair_state (
  route_id           TEXT        NOT NULL,
  direction_id       SMALLINT    NOT NULL,
  vehicle_leader     TEXT        NOT NULL,
  vehicle_follower   TEXT        NOT NULL,
  trip_leader        TEXT        NOT NULL,
  trip_follower      TEXT        NOT NULL,
  consecutive_below  SMALLINT    NOT NULL DEFAULT 0,
  first_below_at     TIMESTAMPTZ NULL,           -- becomes episode_start
  pending_min_gap_seconds INT    NULL,           -- smallest gap while counting, becomes min_gap_seconds
  pending_stop_id    TEXT        NULL,           -- follower's next stop at first_below_at, becomes open_stop_id
  open_episode_id    UUID        NULL,
  last_evaluated_at  TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (route_id, direction_id, vehicle_leader, vehicle_follower)
);

-- Event-time cursor of the bunching detector: last 15-second grid point evaluated (DOC-23 §2.2).
CREATE TABLE insight.analytics_bunching_cursor (
  route_id   TEXT        PRIMARY KEY,
  last_tick  TIMESTAMPTZ NOT NULL CHECK (extract(epoch FROM last_tick)::bigint % 15 = 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ticketing windows and baselines read by created_at across all sale points (DOC-23 §9.1).
CREATE INDEX fact_ticket_sales_created_idx ON dw.fact_ticket_sales (created_at);

-- Insight lists ordered by time with a keyset cursor (DOC-32 §4).
CREATE INDEX insight_bus_bunching_start_idx          ON insight.insight_bus_bunching (episode_start DESC, id DESC);
CREATE INDEX insight_service_disruption_start_idx    ON insight.insight_service_disruption (episode_start DESC, id DESC);
CREATE INDEX insight_dispatch_suggestion_created_idx ON insight.insight_dispatch_suggestion (created_at DESC, id DESC);
