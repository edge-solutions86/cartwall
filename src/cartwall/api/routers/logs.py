"""Reading a log, signing it and sending it out.

The log is the one document two audiences want in two shapes. The operator and
the automation want every element in air order including the network breaks and
the legal IDs. Sales wants the spots and nothing else. Both come off the same
route, and the ``spots`` query decides which.

Building is a POST rather than a side effect of a GET. It writes, it can take a
second on a heavy week, and a GET that quietly rebuilds a log is a GET somebody
will call from a dashboard on a timer.
"""

from __future__ import annotations

from datetime import date, datetime

from fastapi import APIRouter, Body, Query

from ...broadcast import BroadcastWeek
from ...domain.rulebook import House
from ...errors import ConflictError, LookupFailure
from ...logs.approval import LogState, problems, warnings
from ...logs.day import DayLog
from ...persistence.logstore import LogRepository
from ...persistence.unitofwork import UnitOfWork
from ...services.logbuild import build_week
from ..deps import Work
from ..schemas import Shape

router = APIRouter(prefix="/logs", tags=["logs"])


class LogLineOut(Shape):
    at: str
    hour: int
    break_number: int
    element: str
    length: str
    order: str = ""
    line: int = 0
    advertiser: str = ""
    cart: str = ""


class LogOut(Shape):
    station: str
    day: date
    state: LogState
    revision: int
    approved_by: str
    sold_units: int
    open_units: int
    lines: list[LogLineOut]


class ProblemOut(Shape):
    code: str
    where: str
    detail: str


class ReadyOut(Shape):
    station: str
    day: date
    ready: bool
    problems: list[ProblemOut]
    warnings: list[ProblemOut]


class BuildOut(Shape):
    week: str
    placed: int
    wanted: int
    complete: bool
    written: int
    skipped: list[str]


class SignIn(Shape):
    who: str
    at: datetime | None = None


def _house(uow: UnitOfWork) -> House:
    """Rebuild the house from what is on file. Cheap, and never stale."""
    cluster = uow.stations.cluster()
    calls = [station.call for station in cluster.logged]
    return House(
        cluster=cluster,
        accounts=uow.accounts.book(),
        cards=uow.cards.book(),
        dayparts={call: uow.stations.dayparts(call) for call in calls},
        programming={call: _grid(uow, call) for call in calls},
        separation={},
    )


def _grid(uow: UnitOfWork, call: str):
    from sqlalchemy import select

    from ...domain.programming import ClockGrid
    from ...persistence.models.reference import AssignmentRow

    effective = uow.session.scalars(
        select(AssignmentRow.effective)
        .where(AssignmentRow.station == call)
        .order_by(AssignmentRow.effective.desc())
    ).first()
    if effective is None:
        return ClockGrid(call, date(2000, 1, 1))
    return uow.stations.grid(call, effective)


@router.get("/{station}/{day}", response_model=LogOut)
def read_log(
    station: str,
    day: date,
    spots: bool = Query(False, description="Spots only, without the fixed elements"),
    uow: UnitOfWork = Work,
) -> LogOut:
    house = _house(uow)
    logs = LogRepository(uow.session)
    grid = house.programming[house.logging_call(station)]
    board = logs.board(grid, day)
    status = logs.status(grid.station, day)
    built = DayLog.build(grid, board)
    wanted = built.spots() if spots else built.lines
    return LogOut(
        station=grid.station,
        day=day,
        state=status.state,
        revision=status.revision,
        approved_by=status.approved_by,
        sold_units=built.sold_units,
        open_units=built.open_units,
        lines=[
            LogLineOut(
                at=entry.at.twelve_hour(),
                hour=entry.hour,
                break_number=entry.break_number,
                element=entry.element.value,
                length=str(entry.length),
                order=entry.order,
                line=entry.line,
                advertiser=entry.advertiser,
                cart=entry.cart,
            )
            for entry in wanted
        ],
    )


def _problem_out(problem) -> ProblemOut:
    # Problem is a slots dataclass, so there is no __dict__ to splat.
    return ProblemOut(code=problem.code, where=problem.where, detail=problem.detail)


@router.get("/{station}/{day}/ready", response_model=ReadyOut)
def check_log(station: str, day: date, uow: UnitOfWork = Work) -> ReadyOut:
    """What is wrong with this log, and what is merely worth knowing."""
    house = _house(uow)
    logs = LogRepository(uow.session)
    grid = house.programming[house.logging_call(station)]
    built = DayLog.build(grid, logs.board(grid, day))
    return ReadyOut(
        station=grid.station,
        day=day,
        ready=not problems(built),
        problems=[_problem_out(problem) for problem in problems(built)],
        warnings=[_problem_out(problem) for problem in warnings(built)],
    )


@router.post("/build/{week_of}", response_model=BuildOut)
def build(week_of: date, uow: UnitOfWork = Work) -> BuildOut:
    """Rebuild the broadcast week containing ``week_of`` across the cluster."""
    week = BroadcastWeek.of(week_of)
    report = build_week(uow, _house(uow), week)
    return BuildOut(
        week=str(week),
        placed=report.placed,
        wanted=report.wanted,
        complete=report.is_complete,
        written=len(report.written),
        skipped=[f"{call} {day} {state}" for call, day, state in report.skipped],
    )


@router.post("/{station}/{day}/approve", response_model=LogOut)
def approve(station: str, day: date, body: SignIn = Body(), uow: UnitOfWork = Work) -> LogOut:
    house = _house(uow)
    logs = LogRepository(uow.session)
    grid = house.programming[house.logging_call(station)]
    if logs.status(grid.station, day).state is not LogState.DRAFT:
        raise ConflictError(
            f"{grid.station} {day} is not a draft", station=grid.station, day=str(day)
        )
    found = problems(DayLog.build(grid, logs.board(grid, day)))
    if found:
        raise ConflictError(
            f"{grid.station} {day} has {len(found)} problem(s) to fix first",
            station=grid.station,
            problems=[str(problem) for problem in found],
        )
    if not body.who:
        raise ConflictError("a log has to be approved by somebody")
    logs.approve(grid.station, day, body.who, body.at or datetime.now())
    return read_log(grid.station, day, False, uow)


@router.post("/{station}/{day}/release", response_model=LogOut)
def release(station: str, day: date, body: SignIn = Body(), uow: UnitOfWork = Work) -> LogOut:
    house = _house(uow)
    logs = LogRepository(uow.session)
    grid = house.programming[house.logging_call(station)]
    if logs.status(grid.station, day).state is not LogState.APPROVED:
        raise ConflictError(
            f"{grid.station} {day} has not been approved",
            station=grid.station,
            day=str(day),
        )
    logs.release(grid.station, day, body.at or datetime.now())
    return read_log(grid.station, day, False, uow)


@router.post("/{station}/{day}/revise", response_model=LogOut)
def revise(station: str, day: date, uow: UnitOfWork = Work) -> LogOut:
    house = _house(uow)
    logs = LogRepository(uow.session)
    grid = house.programming[house.logging_call(station)]
    status = logs.status(grid.station, day)
    if not status.state.is_out:
        raise ConflictError(
            f"{grid.station} {day} is {status.state.value} and does not need a revision",
            station=grid.station,
        )
    logs.revise(grid.station, day)
    return read_log(grid.station, day, False, uow)


@router.get("/{station}", response_model=list[dict])
def list_logs(
    station: str,
    first: date = Query(description="First day of the span"),
    last: date = Query(description="Last day of the span"),
    uow: UnitOfWork = Work,
) -> list[dict]:
    if last < first:
        raise LookupFailure("the span runs backwards", first=str(first), last=str(last))
    return [
        {
            "day": str(status.day),
            "state": status.state.value,
            "revision": status.revision,
            "approved_by": status.approved_by,
        }
        for status in LogRepository(uow.session).between(station, first, last)
    ]
