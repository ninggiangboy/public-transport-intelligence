-- pti_sim grants (DOC-17). Repeatable.
DO $$
BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO source_simulator, experiment_runner', current_database());
END $$;

GRANT USAGE ON SCHEMA sim TO source_simulator, experiment_runner;

GRANT SELECT, INSERT, UPDATE ON sim.sim_scenario_run TO source_simulator;
GRANT INSERT ON sim.sim_ledger TO source_simulator;
GRANT EXECUTE ON FUNCTION sim.ensure_ledger_partitions(DATE, DATE), sim.drop_ledger_partitions_before(DATE)
  TO source_simulator;

GRANT SELECT ON sim.sim_scenario_run, sim.sim_ledger TO experiment_runner;
