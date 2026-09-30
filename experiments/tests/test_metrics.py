import math

from pti_exp.metrics import Scrape, delta_buckets, parse, quantile

TEXT = """# HELP x
pti_etl_records_total{mode="stream",outcome="written",source="GTFS_RT_VEHICLE_POSITION"} 10.0
pti_etl_records_total{mode="stream",outcome="duplicate",source="GTFS_RT_TRIP_UPDATE"} 4.0
pti_etl_records_total{mode="stream",outcome="written",source="TICKETING_SALES"} 7.0
h_bucket{le="1.0"} 50
h_bucket{le="2.0"} 90
h_bucket{le="+Inf"} 100
"""


def test_sums_series_matching_labels_and_regexes():
    scrape = Scrape(parse(TEXT))
    assert scrape.sum("pti_etl_records_total") == 21
    assert scrape.sum("pti_etl_records_total", source="~GTFS_RT_.*") == 14
    assert scrape.sum("pti_etl_records_total", outcome="duplicate") == 4
    assert scrape.max("missing") is None


def test_quantiles_interpolate_inside_the_bucket_like_histogram_quantile():
    buckets = Scrape(parse(TEXT)).buckets("h")
    assert buckets == {1.0: 50, 2.0: 90, math.inf: 100}
    assert quantile(0.5, buckets) == 1.0
    assert math.isclose(quantile(0.7, buckets), 1.5)
    assert quantile(0.95, buckets) == 2.0  # in +Inf: the highest finite bound


def test_bucket_deltas_restart_from_zero_after_a_counter_reset():
    assert delta_buckets({1.0: 10, 2.0: 20}, {1.0: 15, 2.0: 5}) == {1.0: 5, 2.0: 5}
    assert quantile(0.5, {}) is None
