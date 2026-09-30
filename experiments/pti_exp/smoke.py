"""The smoke chain (DOC-45 §1.3, DR-95): one shortened run of each of EXP-03, EXP-02, EXP-01, EXP-05 and EXP-04, back
to back on one stack, in at most 30 minutes. The runners are those of the full runs with the `smoke` parameters."""

from __future__ import annotations

import json
import random
import time as clock
from collections.abc import Callable
from datetime import datetime, time, timedelta

from pti_exp import profiles
from pti_exp.clock import business_now, in_window, next_offset
from pti_exp.compose import Compose, wait_ready
from pti_exp.config import EXP_ENV, RESULTS, Stack, format_offset
from pti_exp.experiments import exp01, exp02, exp03, exp04, exp05
from pti_exp.runner import InvalidRun, Run, drain, groups, iso, now
from pti_exp.sim import Simulator

# Business time when the chain starts: EXP-05 then begins around 15:30 with ≥ 500 vehicles (DOC-45 §1.3).
WINDOW = (time(15, 15), time(17, 0))
NEEDED = timedelta(minutes=30)
APPS = ("source-simulator", "etl-stream", "etl-stream-baseline", "etl-batch")
CHAIN = (("EXP-03", None), ("EXP-02", "short"), ("EXP-01", "kill-external"), ("EXP-05", "etl-only"),
         ("EXP-04", "window"))


def ensure_window(stack: Stack, log: Callable[[str], None]) -> dict:
    """Moves business time forward to 15:15 of the next weekday when it is outside the window (§1.1: never back)."""
    before = stack.clock_offset
    if in_window(now(), before, *WINDOW, NEEDED):
        return {"moved": False, "offset": format_offset(before)}
    offset = next_offset(now(), before, WINDOW[0])
    log(f"business time {business_now(now(), before):%a %H:%M} is outside {WINDOW[0]}–{WINDOW[1]}: "
        f"offset {format_offset(before)} → {format_offset(offset)}")
    running = move_clock(stack, offset)
    return {"moved": True, "from": format_offset(before), "offset": format_offset(offset),
            "restarted": running}


def move_clock(stack: Stack, offset: timedelta) -> list[str]:
    """Drains the pipeline, writes the offset and recreates the apps with it (DOC-45 §1.1). Messages still in Kafka
    would otherwise be read on the new clock, hours away from their event time, and go to DLQ as DQ-07. Returns after
    one more minute, so that the replay margin of EXP-04 (1 minute) never reaches back before the move."""
    compose = Compose()
    running = [a for a in APPS if compose.running(a)]
    Simulator(stack.sim_url).rate(gtfs_rt=0, ticketing=0)
    clock.sleep(3)
    drain(groups("etl-stream-baseline" in running))
    stack.write_offset(offset)
    compose.up(*running, env=EXP_ENV)
    for app in running:
        wait_ready(stack.actuator(app))
    clock.sleep(60)
    return running


def restore_rate(stack: Stack) -> None:
    """Runners leave the simulator at rate 0 so that nothing overwrites the window before it is measured; the next run
    starts from the base load (DOC-45 §2.1)."""
    Simulator(stack.sim_url).rate(gtfs_rt=1.0, ticketing=1.0)


def run_one(exp: str, variant: str | None, series: str, seed: int, stack: Stack, windows: dict[str, dict]) -> dict:
    params = profiles.params(exp, "smoke")
    r = Run.create(exp, series, 1, "smoke", params | {"variant": variant}, seed, stack)
    started = now()
    try:
        match exp:
            case "EXP-01":
                summary = exp01.run(r, variant, random.Random(seed))
                problems = exp01.criteria(summary, variant)
            case "EXP-02":
                summary = exp02.run(r, variant)
                problems = exp02.criteria(summary)
            case "EXP-03":
                summary = exp03.run(r)
                problems = exp03.criteria(summary)
            case "EXP-04":
                w0 = _time(windows["EXP-03"]["t0"]) - timedelta(minutes=1)
                w1 = _time(windows["EXP-02"]["t1"]) + timedelta(minutes=1)
                summary = exp04.run_window(r, w0, w1)
                problems = exp04.criteria(summary)
            case "EXP-05":
                summary = exp05.run(r, variant)
                problems = exp05.criteria(summary)
            case _:
                raise ValueError(exp)
        r.summary["criteria_problems"] = problems
        r.write_summary()
        valid = True
    except InvalidRun as e:
        problems = [f"invalid: {e}"]
        r.summary["criteria_problems"] = problems
        r.write_summary(valid=False, reason=str(e))
        valid = False
    finally:
        restore_rate(stack)
    return {"exp": exp, "variant": variant, "run_id": r.run_id, "valid": valid, "passed": valid and not problems,
            "problems": problems, "seconds": round((now() - started).total_seconds(), 1),
            "window": r.summary.get("window")}


def smoke(series: str | None, seed: int, stack: Stack, log: Callable[[str], None] = print,
          only: list[str] | None = None) -> dict:
    series = series or datetime.now().strftime("%Y-%m-%dT%H%M")
    directory = RESULTS / "smoke" / series
    directory.mkdir(parents=True, exist_ok=True)
    started, begun = now(), clock.monotonic()
    clock_state = ensure_window(stack, log)
    log(f"series {series}: business time {business_now(now(), stack.clock_offset):%Y-%m-%d %H:%M %Z}")
    results, windows = [], {}
    for exp, variant in CHAIN:
        if only and exp not in only:
            continue
        if exp == "EXP-04" and not {"EXP-02", "EXP-03"} <= windows.keys():
            results.append({"exp": exp, "variant": variant, "valid": False, "passed": False,
                            "problems": ["skipped: needs the windows of EXP-03 and EXP-02"], "seconds": 0})
            continue
        log(f"{exp} ({variant or 'ratio 0.05'}) …")
        result = run_one(exp, variant, series, seed, stack, windows)
        if result.get("window"):
            windows[exp] = result["window"]
        results.append(result)
        log(f"{exp}: {'PASS' if result['passed'] else 'FAIL'} in {result['seconds']:.0f} s"
            + "".join(f"\n  - {p}" for p in result["problems"]))
    seconds = clock.monotonic() - begun
    chain = {"series": series, "started_at": iso(started), "finished_at": iso(now()), "seconds": round(seconds, 1),
             "within_30_minutes": seconds <= 30 * 60, "clock": clock_state,
             "passed": all(r["passed"] for r in results) and len(results) == len(CHAIN), "runs": results}
    (directory / "chain.json").write_text(json.dumps(chain, indent=2, default=str) + "\n")
    return chain


def _time(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))
