"""Business time during a series (DOC-45 §1.1, DR-67): check the window, and move the clock forward, never back."""

from __future__ import annotations

from datetime import UTC, date, datetime, time, timedelta
from zoneinfo import ZoneInfo

CHICAGO = ZoneInfo("America/Chicago")
MAX_OFFSET = timedelta(hours=24)
FEED_LAST_DAY = date(2026, 11, 13)
DST_CHANGE = date(2026, 11, 1)


def business_now(real: datetime, offset: timedelta) -> datetime:
    return (real + offset).astimezone(CHICAGO)


def weekday(day: date) -> bool:
    """Tuesday to Friday: Monday mixes weekend-shaped early hours after a weekend (DOC-45 §1.1)."""
    return day.weekday() in (1, 2, 3, 4)


def in_window(real: datetime, offset: timedelta, start: time, end: time, needed: timedelta) -> bool:
    """Whether business time is a weekday inside [start, end − needed]."""
    now = business_now(real, offset)
    latest = (datetime.combine(now.date(), end) - needed).time()
    return weekday(now.date()) and start <= now.time() <= latest and now.date() != DST_CHANGE


def next_offset(real: datetime, offset: timedelta, target: time) -> timedelta:
    """The smallest offset ≥ the current one that puts business time at `target` on a valid weekday.

    Raises when that needs more than ±24 hours or runs past the feed: the series must end and a new one start from
    `make reset` (DOC-45 §1.1).
    """
    now = business_now(real, offset)
    day = now.date()
    for _ in range(8):
        candidate = datetime.combine(day, target, tzinfo=CHICAGO)
        if candidate >= now and weekday(day) and day != DST_CHANGE:
            wanted = offset + (candidate - now)
            wanted = timedelta(minutes=round(wanted.total_seconds() / 60))
            if abs(wanted) > MAX_OFFSET or day > FEED_LAST_DAY:
                raise RuntimeError(
                    f"No valid business window within ±24 h of real time (needed offset {wanted}); "
                    "end the series and start a new one from make reset (DOC-45 §1.1)")
            return wanted
        day += timedelta(days=1)
    raise RuntimeError("No weekday found")


def utc(dt: datetime) -> datetime:
    return dt.astimezone(UTC)
