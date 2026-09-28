-- Calendar dimension 2024-2030 (DR-11). Holidays are the days Metro Transit runs its Sunday
-- schedule. This file is the single place that defines them; changing the list needs a new
-- migration that updates the affected rows.

CREATE TABLE dw.dim_date (
  date_key     INT      PRIMARY KEY CHECK (date_key BETWEEN 19000101 AND 29991231),  -- yyyymmdd
  date         DATE     NOT NULL UNIQUE,
  day_of_week  SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),                  -- ISO: 1 = Monday
  is_weekend   BOOLEAN  NOT NULL,
  is_holiday   BOOLEAN  NOT NULL,
  holiday_name TEXT     NULL,
  day_type     TEXT     NOT NULL CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY_HOLIDAY')),
  CHECK (is_holiday = (holiday_name IS NOT NULL))
);

WITH years AS (
  SELECT y FROM generate_series(2024, 2030) AS y
),
holidays (date, name) AS (
  SELECT make_date(y, 1, 1), 'New Year''s Day' FROM years
  UNION ALL  -- last Monday of May
  SELECT d, 'Memorial Day' FROM years,
         LATERAL (SELECT max(d)::date AS d FROM generate_series(make_date(y, 5, 25), make_date(y, 5, 31), interval '1 day') d
                  WHERE extract(isodow FROM d) = 1) m
  UNION ALL
  SELECT make_date(y, 7, 4), 'Independence Day' FROM years
  UNION ALL  -- first Monday of September
  SELECT d, 'Labor Day' FROM years,
         LATERAL (SELECT min(d)::date AS d FROM generate_series(make_date(y, 9, 1), make_date(y, 9, 7), interval '1 day') d
                  WHERE extract(isodow FROM d) = 1) m
  UNION ALL  -- fourth Thursday of November
  SELECT d, 'Thanksgiving Day' FROM years,
         LATERAL (SELECT min(d)::date AS d FROM generate_series(make_date(y, 11, 22), make_date(y, 11, 28), interval '1 day') d
                  WHERE extract(isodow FROM d) = 4) m
  UNION ALL
  SELECT make_date(y, 12, 25), 'Christmas Day' FROM years
),
days AS (
  SELECT d::date AS date FROM generate_series(date '2024-01-01', date '2030-12-31', interval '1 day') AS d
)
INSERT INTO dw.dim_date (date_key, date, day_of_week, is_weekend, is_holiday, holiday_name, day_type)
SELECT to_char(days.date, 'YYYYMMDD')::int,
       days.date,
       extract(isodow FROM days.date)::smallint,
       extract(isodow FROM days.date) IN (6, 7),
       h.name IS NOT NULL,
       h.name,
       CASE WHEN h.name IS NOT NULL OR extract(isodow FROM days.date) = 7 THEN 'SUNDAY_HOLIDAY'
            WHEN extract(isodow FROM days.date) = 6 THEN 'SATURDAY'
            ELSE 'WEEKDAY' END
FROM days
LEFT JOIN holidays h ON h.date = days.date;
