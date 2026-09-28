package dev.pti.db;

import static dev.pti.db.GrantCase.allowed;
import static dev.pti.db.GrantCase.connectDenied;
import static dev.pti.db.GrantCase.denied;

import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** DOC-17 §7.2: the pg-source permission matrix (ticketing_source and pti_sim), case numbers as in the document. */
class SourceGrantsIT {

    private static final String TICKETING = "ticketing_source";
    private static final String SIM = "pti_sim";
    private static final String SIMULATOR = "source_simulator";
    private static final String DEBEZIUM = "debezium";
    private static final String EXPERIMENT = "experiment_runner";

    private static final String INSERT_SALE_POINT =
            "INSERT INTO public.sale_point (sale_point_id, name, kind, stop_id) VALUES ('KIOSK-GT1', 'Grants test', 'KIOSK', '1')";

    static Stream<GrantCase> cases() {
        return Stream.of(
                allowed(1, TICKETING, SIMULATOR, "insert sale point + sale", INSERT_SALE_POINT + ";" + """
                        INSERT INTO public.ticket_transaction (transaction_id, sale_point_id, ticket_type, txn_type, amount)
                        VALUES (gen_random_uuid(), 'KIOSK-GT1', 'SINGLE', 'SALE', 2.50)"""),
                denied(
                        2,
                        TICKETING,
                        SIMULATOR,
                        "write heartbeat",
                        "UPDATE public.debezium_heartbeat SET ts = now() WHERE id = 1"),
                denied(3, TICKETING, SIMULATOR, "drop table", "DROP TABLE public.ticket_transaction"),
                denied(4, TICKETING, SIMULATOR, "create table", "CREATE TABLE public.grants_probe (id INT)"),
                denied(
                        5,
                        TICKETING,
                        SIMULATOR,
                        "alter publication",
                        "ALTER PUBLICATION pti_ticketing DROP TABLE public.debezium_heartbeat"),
                allowed(6, TICKETING, DEBEZIUM, "snapshot select", "SELECT * FROM public.ticket_transaction LIMIT 1"),
                allowed(
                        7,
                        TICKETING,
                        DEBEZIUM,
                        "heartbeat action query",
                        "UPDATE public.debezium_heartbeat SET ts = now() WHERE id = 1"),
                denied(8, TICKETING, DEBEZIUM, "insert business row", INSERT_SALE_POINT),
                // Temporary: the slot disappears with the session even though creating it is not transactional.
                allowed(
                        9,
                        TICKETING,
                        DEBEZIUM,
                        "create pgoutput slot",
                        "SELECT pg_create_logical_replication_slot('grants_probe', 'pgoutput', true)"),
                allowed(
                        10,
                        TICKETING,
                        EXPERIMENT,
                        "read ground truth",
                        "SELECT * FROM public.ticket_transaction LIMIT 1"),
                denied(11, TICKETING, EXPERIMENT, "write", INSERT_SALE_POINT),
                allowed(12, SIM, SIMULATOR, "insert ledger", """
                        INSERT INTO sim.sim_ledger (produced_at, message_id, entity_type, kafka_topic, kafka_partition,
                          kafka_offset, business_keys, event_timestamp, schema_version, payload_hash)
                        VALUES (now(), gen_random_uuid(), 'VEHICLE_POSITION', 'gtfs.vehicle_positions', 0, 0,
                          ARRAY['v1|2026-09-29T12:00:00.000Z'], now(), 1, repeat('a', 64))"""),
                denied(13, SIM, SIMULATOR, "update ledger", "UPDATE sim.sim_ledger SET is_resend = false WHERE false"),
                denied(14, SIM, SIMULATOR, "delete ledger", "DELETE FROM sim.sim_ledger WHERE false"),
                allowed(
                        15,
                        SIM,
                        SIMULATOR,
                        "drop old ledger partitions",
                        "SELECT sim.drop_ledger_partitions_before(DATE '2020-01-01')"),
                denied(16, SIM, SIMULATOR, "create table", "CREATE TABLE sim.grants_probe (id INT)"),
                allowed(17, SIM, EXPERIMENT, "read ledger", "SELECT * FROM sim.sim_ledger LIMIT 1"),
                denied(
                        18,
                        SIM,
                        EXPERIMENT,
                        "call partition fn",
                        "SELECT sim.ensure_ledger_partitions(DATE '2026-01-01', DATE '2026-01-01')"),
                connectDenied(19, SIM, DEBEZIUM));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void permissionMatrix(GrantCase grantCase) throws SQLException {
        grantCase.verify();
    }
}
