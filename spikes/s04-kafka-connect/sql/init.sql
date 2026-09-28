-- Minimal ticketing_source for the Debezium check (DOC-13 §5).
CREATE DATABASE ticketing_source;
\c ticketing_source
CREATE TABLE ticket_transaction (
    transaction_id UUID PRIMARY KEY,
    sale_point_id  TEXT NOT NULL,
    amount         NUMERIC(10, 2) NOT NULL,
    customer_ref   TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sale_point (sale_point_id TEXT PRIMARY KEY, name TEXT);
CREATE TABLE debezium_heartbeat (id INT PRIMARY KEY, ts TIMESTAMPTZ NOT NULL);
INSERT INTO debezium_heartbeat VALUES (1, now());
ALTER TABLE ticket_transaction REPLICA IDENTITY FULL;
CREATE PUBLICATION pti_ticketing FOR TABLE ticket_transaction, sale_point, debezium_heartbeat;
CREATE ROLE debezium WITH LOGIN REPLICATION PASSWORD 'debezium-secret';
GRANT CONNECT ON DATABASE ticketing_source TO debezium;
GRANT USAGE ON SCHEMA public TO debezium;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO debezium;
GRANT UPDATE ON debezium_heartbeat TO debezium;
INSERT INTO ticket_transaction (transaction_id, sale_point_id, amount, customer_ref)
VALUES ('5b0e7f2a-3c1d-4e8f-9a6b-1c2d3e4f5a6b', 'KIOSK-017', 2.50, 'c-000184223');
