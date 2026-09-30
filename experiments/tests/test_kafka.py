from pti_exp.kafka import committed_lag, parse_describe

OUTPUT = """
GROUP           TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID HOST CLIENT-ID
pti-etl-gtfs-rt gtfs.vehicle_positions 0          100             120             20   c-1 /10.0.0.1 etl
pti-etl-gtfs-rt gtfs.trip_updates      0          -               30              -    -   -         -

GROUP            TOPIC                  PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
pti-exp-baseline gtfs.vehicle_positions 0          120             120             0
"""


def test_reads_every_group_of_the_describe_output():
    state = parse_describe(OUTPUT)
    assert state["pti-etl-gtfs-rt"][("gtfs.vehicle_positions", 0)] == (100, 120)
    assert state["pti-etl-gtfs-rt"][("gtfs.trip_updates", 0)] == (None, 30)
    assert state["pti-exp-baseline"][("gtfs.vehicle_positions", 0)] == (120, 120)


def test_a_partition_without_a_commit_counts_its_whole_log():
    state = parse_describe(OUTPUT)
    assert committed_lag(state["pti-etl-gtfs-rt"]) == 50
    assert committed_lag(state["pti-etl-gtfs-rt"], ("gtfs.vehicle_positions",)) == 20
