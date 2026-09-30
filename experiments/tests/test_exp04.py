from pti_exp.experiments.exp04 import checksum, compare, criteria


def _state(rows, dlq=None):
    return {"tables": {"fact_vehicle_position": rows}, "dlq": dlq or {}}


def test_a_rebuild_that_restores_every_row_matches():
    before = _state({"a": "1", "b": "2"}, {"t|0|1": "QUALITY|DQ-06"})
    after = _state({"a": "1", "b": "2", "outside": "9"}, {"t|0|1": "QUALITY|DQ-06"})
    result = compare(before, after)
    assert result["tables"]["fact_vehicle_position"]["table_match"]
    assert result["dlq_symdiff"] == 0
    assert criteria(result | {"gtfs_rows_match": True}) == []


def test_missing_and_changed_rows_and_dead_letters_fail_c1_and_c2():
    result = compare(_state({"a": "1", "b": "2"}, {"t|0|1": "QUALITY|DQ-06"}),
                     _state({"a": "other"}, {"t|0|1": "SCHEMA|DQ-01"}))
    table = result["tables"]["fact_vehicle_position"]
    assert (table["missing"], table["different"]) == (1, 1)
    assert result["dlq_symdiff"] == 2
    problems = criteria(result | {"gtfs_rows_match": False})
    assert [p[:2] for p in problems] == ["C1", "C2", "C4"]


def test_the_checksum_does_not_depend_on_key_order():
    assert checksum({"a": "1", "b": "2"}) == checksum({"b": "2", "a": "1"})
    assert checksum({"a": "1"})["row_count"] == 1
