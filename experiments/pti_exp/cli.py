"""`pti-exp` (DOC-45 §2). P3 has `env check`, `run`, `smoke` and `analyze`; `archive`, `fetch` and `report` come
with the full runs (P3-10, DR-95), `check` with the demo (P8)."""

from __future__ import annotations

import json
import random
import shutil
from datetime import datetime, time, timedelta
from typing import Annotated

import httpx
import typer

from pti_exp import profiles
from pti_exp import smoke as smoke_chain
from pti_exp.alerts import Alerts
from pti_exp.clock import business_now, in_window, next_offset
from pti_exp.compose import Compose, wait_ready
from pti_exp.config import EXP_ENV, RESULTS, Stack, format_offset
from pti_exp.experiments import exp01, exp02, exp03, exp04, exp05
from pti_exp.runner import InvalidRun, Run, git_state, now

app = typer.Typer(no_args_is_help=True, add_completion=False, help="Experiment runner (DOC-45).")
env_app = typer.Typer(no_args_is_help=True, help="The stack the experiments run on.")
app.add_typer(env_app, name="env")

# Business-time windows of the full runs (protocol files §2): start, end.
WINDOWS = {"EXP-01": (time(13), time(19)), "EXP-02": (time(13), time(19)), "EXP-03": (time(13), time(19)),
           "EXP-05": (time(15, 30), time(17, 30))}
VARIANTS = {"EXP-01": ["kill-external", "halt", "db-outage", "control"], "EXP-02": ["short", "long"],
            "EXP-03": [None], "EXP-05": ["etl-only"]}
APPS = ("source-simulator", "etl-stream", "etl-stream-baseline", "etl-batch")


def _checks(stack: Stack, needed: timedelta | None = None) -> list[tuple[str, bool, str]]:
    sha, dirty = git_state()
    checks = [("git tree clean", not dirty, sha[:12] + (" (dirty)" if dirty else ""))]
    compose = Compose()
    for service in ("kafka", "pg-warehouse", "pg-source", "kafka-connect", *APPS, "toxiproxy"):
        checks.append((f"{service} running", compose.running(service), ""))
    for app_name in ("etl-stream", "etl-batch", "source-simulator"):
        try:
            response = httpx.get(stack.actuator(app_name) + "/actuator/health/readiness", timeout=3)
            checks.append((f"{app_name} ready", response.status_code == 200, str(response.status_code)))
        except httpx.HTTPError as e:
            checks.append((f"{app_name} ready", False, type(e).__name__))
    checks.append(("alertmanager", Alerts(stack.alertmanager_url, stack.prometheus_url).available(),
                   "optional in the smoke chain"))
    business = business_now(now(), stack.clock_offset)
    window = in_window(now(), stack.clock_offset, *smoke_chain.WINDOW, needed or smoke_chain.NEEDED)
    checks.append(("business time in the smoke window", window, f"{business:%a %Y-%m-%d %H:%M %Z}"))
    free = shutil.disk_usage(RESULTS.parent).free / 2**30
    checks.append(("free disk ≥ 20 GiB", free >= 20, f"{free:.0f} GiB"))
    return checks


@env_app.command("check")
def env_check() -> None:
    """Clean tree, stack healthy, clock window, disk space (DOC-45 §2)."""
    failed = False
    for name, ok, detail in _checks(Stack()):
        optional = name in ("alertmanager", "business time in the smoke window", "git tree clean")
        failed |= not ok and not optional
        typer.echo(f"{'OK  ' if ok else ('WARN' if optional else 'FAIL')} {name}{f': {detail}' if detail else ''}")
    raise typer.Exit(1 if failed else 0)


@app.command()
def smoke(
    series: Annotated[str | None, typer.Option(help="Series name; default is the start time")] = None,
    seed: Annotated[int, typer.Option(help="Seed of the runner's random choices")] = 1000,
    only: Annotated[list[str] | None, typer.Option(help="Run only these EXP (debugging; not a valid chain)")] = None,
    allow_dirty: Annotated[bool, typer.Option(help="Run from a dirty tree (results flagged dirty)")] = False,
) -> None:
    """The 30-minute smoke chain EXP-03 → EXP-02 → EXP-01 → EXP-05 → EXP-04 (DOC-45 §1.3)."""
    _require_clean(allow_dirty)
    chain = smoke_chain.smoke(series, seed, Stack(), typer.echo, only)
    typer.echo(_table(chain["runs"]))
    typer.echo(f"chain {'PASS' if chain['passed'] else 'FAIL'} in {chain['seconds'] / 60:.1f} min"
               f"{'' if chain['within_30_minutes'] else ' (over 30 minutes)'}; "
               f"results in {RESULTS / 'smoke' / chain['series']}")
    raise typer.Exit(0 if chain["passed"] else 1)


@app.command()
def run(
    exp: Annotated[str, typer.Argument(help="EXP-01 … EXP-05")],
    runs: Annotated[int, typer.Option(help="Valid runs wanted")] = 1,
    seed: Annotated[int, typer.Option(help="Seed of the series; run i uses seed + i")] = 1000,
    variant: Annotated[str | None, typer.Option(help="Variant (EXP-01, EXP-02) or series (EXP-05)")] = None,
    profile: Annotated[str, typer.Option(help="full or smoke")] = "full",
    series: Annotated[str | None, typer.Option(help="Series name; default is the start time")] = None,
    resume: Annotated[bool, typer.Option(help="Skip runs that have a summary.json")] = False,
    allow_dirty: Annotated[bool, typer.Option(help="Run from a dirty tree (results flagged dirty)")] = False,
) -> None:
    """Runs one experiment `runs` times (DOC-45 §2); an invalid run is kept and replaced by the next one."""
    _require_clean(allow_dirty)
    if exp not in profiles.PROFILES:
        raise typer.BadParameter(f"unknown experiment {exp}")
    variant = variant or (VARIANTS.get(exp, [None])[0] if exp != "EXP-04" else None)
    series = series or datetime.now().strftime("%Y-%m-%dT%H%M")
    stack, valid, index = Stack(), 0, 0
    while valid < runs:
        index += 1
        r = Run.create(exp, series, index, profile, profiles.params(exp, profile) | {"variant": variant},
                       seed + index, stack)
        if r.done:
            if resume:
                valid += json.loads((r.directory / "summary.json").read_text()).get("valid", False)
                continue
            raise typer.BadParameter(f"{r.directory} exists; use --resume or another --series")
        if resume and (r.directory / "config.json").exists():
            r.write_summary(valid=False, reason="interrupted")
            continue
        if exp in WINDOWS:
            _ensure_window(stack, exp, r.params.get("window_needed", timedelta(minutes=30)))
        try:
            problems = _run(r, exp, variant, seed + index)
            r.summary["criteria_problems"] = problems
            r.write_summary()
            valid += 1
            typer.echo(f"{r.run_id}: {'PASS' if not problems else 'FAIL ' + '; '.join(problems)}")
        except InvalidRun as e:
            r.write_summary(valid=False, reason=str(e))
            typer.echo(f"{r.run_id}: invalid ({e})")


@app.command()
def analyze(
    exp: Annotated[str, typer.Argument(help="EXP-01 … EXP-05, or smoke")],
    series: Annotated[str | None, typer.Option(help="Series; default is the latest")] = None,
) -> None:
    """A table of the runs of a series: validity and the criteria that failed (the statistics come with P3-10)."""
    if exp == "smoke":
        base = RESULTS / "smoke"
        chosen = series or max((p.name for p in base.iterdir() if p.is_dir()), default=None)
        if chosen is None:
            raise typer.BadParameter("no smoke series")
        typer.echo(_table(json.loads((base / chosen / "chain.json").read_text())["runs"]))
        return
    rows = []
    for summary_file in sorted((RESULTS / exp).glob(f"{series or ''}*/summary.json")):
        s = json.loads(summary_file.read_text())
        problems = s.get("criteria_problems") or ([f"invalid: {s['invalid_reason']}"] if not s["valid"] else [])
        rows.append({"exp": exp, "run_id": s["run_id"], "valid": s["valid"], "passed": s["valid"] and not problems,
                     "problems": problems, "seconds": None})
    typer.echo(_table(rows))


def _run(r: Run, exp: str, variant: str | None, seed: int) -> list[str]:
    match exp:
        case "EXP-01":
            return exp01.criteria(exp01.run(r, variant, random.Random(seed)), variant)
        case "EXP-02":
            return exp02.criteria(exp02.run(r, variant))
        case "EXP-03":
            return exp03.criteria(exp03.run(r))
        case "EXP-04":
            return exp04.criteria(exp04.run_full(r))
        case "EXP-05":
            return exp05.criteria(exp05.run(r, variant))
    raise typer.BadParameter(exp)


def _ensure_window(stack: Stack, exp: str, needed: timedelta) -> None:
    start, end = WINDOWS[exp]
    if in_window(now(), stack.clock_offset, start, end, needed):
        return
    offset = next_offset(now(), stack.clock_offset, start)
    typer.echo(f"moving business time forward: offset {format_offset(stack.clock_offset)} → {format_offset(offset)}")
    stack.write_offset(offset)
    compose = Compose()
    running = [a for a in APPS if compose.running(a)]
    compose.up(*running, env=EXP_ENV)
    for app_name in running:
        wait_ready(stack.actuator(app_name))


def _require_clean(allow_dirty: bool) -> None:
    sha, dirty = git_state()
    if dirty and not allow_dirty:
        typer.echo(f"the working tree at {sha[:12]} is dirty; commit, or pass --allow-dirty (results flagged dirty)")
        raise typer.Exit(2)


def _table(rows: list[dict]) -> str:
    lines = [f"{'EXP':<7} {'variant':<14} {'result':<8} {'time':>6}  problems", "-" * 72]
    for r in rows:
        result = "PASS" if r["passed"] else ("FAIL" if r.get("valid", True) else "INVALID")
        seconds = f"{r['seconds'] / 60:.1f}m" if r.get("seconds") else ""
        problems = "; ".join(r.get("problems") or []) or "-"
        lines.append(f"{r['exp']:<7} {r.get('variant') or r.get('run_id') or ''!s:<14} {result:<8} {seconds:>6}  "
                     f"{problems}")
    return "\n".join(lines)


if __name__ == "__main__":
    app()
