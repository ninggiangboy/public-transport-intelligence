from datetime import UTC, datetime, time, timedelta

import pytest

from pti_exp.clock import CHICAGO, business_now, in_window, next_offset

# Tuesday 2026-09-29 20:00 CDT.
REAL = datetime(2026, 9, 30, 1, 0, tzinfo=UTC)


def test_business_time_is_chicago_time_plus_the_offset():
    assert business_now(REAL, timedelta(hours=1)).hour == 21


def test_the_evening_is_outside_the_smoke_window():
    assert not in_window(REAL, timedelta(0), time(15, 15), time(17), timedelta(minutes=30))


def test_the_window_leaves_room_for_the_whole_chain():
    offset = timedelta(hours=-3, minutes=-45)  # 16:15 CDT: only 45 minutes left before 17:00
    assert in_window(REAL, offset, time(15, 15), time(17), timedelta(minutes=30))
    assert not in_window(REAL, offset + timedelta(minutes=20), time(15, 15), time(17), timedelta(minutes=30))


def test_moves_forward_to_the_next_weekday_and_never_back():
    offset = next_offset(REAL, timedelta(0), time(15, 15))
    assert offset > timedelta(0)
    moved = business_now(REAL, offset)
    assert (moved.date().isoformat(), moved.time()) == ("2026-09-30", time(15, 15))
    assert moved.tzinfo == CHICAGO


def test_skips_the_weekend_and_monday():
    friday_evening = datetime(2026, 10, 3, 1, 0, tzinfo=UTC)  # Friday 2026-10-02 20:00 CDT
    with pytest.raises(RuntimeError):
        next_offset(friday_evening, timedelta(0), time(15, 15))  # Tuesday is more than 24 h away
