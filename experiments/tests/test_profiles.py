from pti_exp.profiles import PROFILES, jsonable, params
from pti_exp.smoke import CHAIN


def test_every_experiment_has_a_full_and_a_smoke_profile():
    for exp, by_profile in PROFILES.items():
        assert set(by_profile) == {"full", "smoke"}, exp


def test_the_smoke_chain_fits_in_30_minutes_of_scenario_time():
    p = {exp: params(exp, "smoke") for exp, _ in CHAIN}
    minutes = (p["EXP-03"]["duration"] + p["EXP-03"]["settle"] + p["EXP-02"]["duration"] + p["EXP-02"]["settle"]
               + p["EXP-01"]["window"] + p["EXP-01"]["settle"]
               + len(p["EXP-05"]["steps"]) * p["EXP-05"]["step"] + p["EXP-05"]["settle"]).total_seconds() / 60
    assert minutes < 25  # drains and the EXP-04 rebuild take the rest


def test_params_are_written_as_json_numbers():
    assert jsonable(params("EXP-01", "smoke"))["window"] == 240.0
