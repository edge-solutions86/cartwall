"""The log commands, which are the ones cron actually runs.

Three of them run unattended every night and their contract with cron is the
exit code. The other three are for the traffic director standing at a terminal
because the web thing is not answering, and those ask before they do anything
that cannot be undone.

    cartwall build 2025-08-04              rebuild the week that day falls in
    cartwall export KWLO 2025-08-04        write the automation's file
    cartwall post KWLO 2025-08-04 file.asr read the as-run back

The build takes a day rather than a week because nobody in the building thinks
in broadcast weeks except this program, and asking cron to work out that the
week of 2025-08-04 starts on 2025-08-04 is asking for the one week a year when
it does not.
"""

from __future__ import annotations

from datetime import date, datetime
from pathlib import Path

import click

from ..broadcast import BroadcastWeek
from ..domain.programming import ClockGrid
from ..logs.approval import problems, warnings
from ..logs.day import DayLog
from ..persistence.logstore import LogRepository
from ..playout import asrun, export
from ..reconcile.matcher import reconcile
from ..reconcile.posting import post
from ..services.house import grid_for, house_from
from ..services.logbuild import build_week
from .main import REFUSED, House, cli, parse_day, pass_house


@cli.command()
@click.argument("day")
@pass_house
def build(house: House, day: str) -> None:
    """Rebuild the broadcast week that DAY falls in, across the cluster."""
    when = _day(day)
    with house.work() as uow:
        report = build_week(uow, house_from(uow), BroadcastWeek.of(when))
        uow.commit()
    click.echo(f"{report.week}")
    click.echo(f"  placed   {report.placed} of {report.wanted}")
    click.echo(f"  written  {len(report.written)} logs")
    for call, skipped, state in report.skipped:
        click.echo(f"  left     {call} {skipped} ({state})")
    for short in report.shortfalls():
        click.echo(f"  short    {short.order.number} {short.placed} of {short.wanted}")


@cli.command()
@click.argument("station")
@click.argument("day")
@click.option("--into", type=click.Path(), default=None, help="Directory to write into.")
@pass_house
def build_export(house: House, station: str, day: str, into: str | None) -> None:
    """Write STATION's log for DAY in the automation's format."""
    when = _day(day)
    with house.work() as uow:
        grid = _grid(uow, station)
        log = DayLog.build(grid, LogRepository(uow.session).board(grid, when))
    text = export.render(log)
    if into is None:
        click.echo(text, nl=False)
        return
    path = Path(into) / export.filename(grid.station, when)
    path.write_text(text, encoding="utf-8", newline="\n")
    click.echo(f"{len(text.splitlines())} lines written to {path}")


@cli.command()
@click.argument("station")
@click.argument("day")
@click.argument("file", type=click.Path(exists=True))
@click.option("--force", is_flag=True, help="Post against a day that has not aired.")
@pass_house
def post_asrun(house: House, station: str, day: str, file: str, force: bool) -> None:
    """Read STATION's as-run for DAY out of FILE and post it."""
    when = _day(day)
    rows = asrun.parse(Path(file).read_text(encoding="utf-8"))
    with house.work() as uow:
        grid = _grid(uow, station)
        log = DayLog.build(grid, LogRepository(uow.session).board(grid, when))
        result = reconcile(log, rows)
        report = post(uow.session, result, force=force)
        uow.commit()
    click.echo(f"{report.station} {report.day}")
    click.echo(f"  aired    {report.aired}")
    click.echo(f"  missed   {report.missed}")
    click.echo(f"  moved    {report.moved}")
    click.echo(f"  unlogged {report.unmatched}")


@cli.command("check")
@click.argument("station")
@click.argument("day")
@pass_house
def check_log(house: House, station: str, day: str) -> None:
    """Say whether STATION's log for DAY can be signed, and what is wrong."""
    when = _day(day)
    with house.work() as uow:
        grid = _grid(uow, station)
        log = DayLog.build(grid, LogRepository(uow.session).board(grid, when))
    found = problems(log)
    for problem in found:
        click.echo(f"problem  {problem}")
    for note in warnings(log):
        click.echo(f"warning  {note}")
    if found:
        click.echo(f"{grid.station} {when} is not ready", err=True)
        raise SystemExit(REFUSED)
    click.echo(f"{grid.station} {when} is ready to sign")


@cli.command()
@click.argument("station")
@click.argument("day")
@click.option("--who", required=True, help="Who is signing it.")
@pass_house
def approve(house: House, station: str, day: str, who: str) -> None:
    """Sign STATION's log for DAY."""
    when = _day(day)
    with house.work() as uow:
        grid = _grid(uow, station)
        log = DayLog.build(grid, LogRepository(uow.session).board(grid, when))
        found = problems(log)
        if found:
            click.echo(f"{grid.station} {when} has {len(found)} problem(s)", err=True)
            raise SystemExit(REFUSED)
        status = LogRepository(uow.session).approve(grid.station, when, who, datetime.now())
        uow.commit()
    click.echo(f"{status.station} {status.day} approved by {status.approved_by}")


@cli.command()
@click.argument("station")
@click.argument("day")
@pass_house
def release(house: House, station: str, day: str) -> None:
    """Send STATION's log for DAY to the automation."""
    when = _day(day)
    with house.work() as uow:
        grid = _grid(uow, station)
        status = LogRepository(uow.session).release(grid.station, when, datetime.now())
        uow.commit()
    click.echo(f"{status.station} {status.day} released at revision {status.revision}")


def _day(text: str) -> date:
    return parse_day(text)


def _grid(uow, station: str, on: date | None = None) -> ClockGrid:
    return grid_for(uow, station, on)
