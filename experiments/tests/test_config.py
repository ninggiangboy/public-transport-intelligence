from datetime import timedelta

import pytest

from pti_exp.config import format_offset, parse_offset


@pytest.mark.parametrize(("text", "expected"), [
    ("", timedelta(0)), ("0s", timedelta(0)), ("-672m", timedelta(minutes=-672)),
    ("19h40m", timedelta(hours=19, minutes=40)), ("90s", timedelta(seconds=90)),
])
def test_parses_the_offsets_that_clock_offset_writes(text, expected):
    assert parse_offset(text) == expected


def test_rejects_an_unknown_offset():
    with pytest.raises(ValueError):
        parse_offset("tomorrow")


def test_formats_whole_minutes_that_parse_back():
    offset = timedelta(hours=19, minutes=15)
    assert parse_offset(format_offset(offset)) == offset
