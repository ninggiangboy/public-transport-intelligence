-- ticketing_source grants (DOC-17). Repeatable: Flyway re-runs it whenever this file changes.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO source_simulator, debezium, experiment_runner',
                 current_database());
END $$;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM source_simulator, debezium, experiment_runner;
GRANT USAGE ON SCHEMA public TO source_simulator, debezium, experiment_runner;

-- source-simulator: the application that "owns" the business data.
GRANT SELECT, INSERT, UPDATE, DELETE ON public.sale_point, public.ticket_transaction TO source_simulator;

-- Debezium: snapshot (SELECT) and heartbeat.action.query (UPDATE on the heartbeat row).
GRANT SELECT ON public.sale_point, public.ticket_transaction, public.debezium_heartbeat TO debezium;
GRANT UPDATE (ts) ON public.debezium_heartbeat TO debezium;

-- Experiment runner: ground truth for ticketing (DR-28).
GRANT SELECT ON public.sale_point, public.ticket_transaction TO experiment_runner;
