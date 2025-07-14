"""What is left to sell, over HTTP, for the sellers' laptops.

Everything here is a read and everything is behind the reader role. A seller in
a client's car park wants to know whether there is drive time next week, and
the answer has to come back before the meeting starts.

Two shapes, and the difference is the one the avails report already warns
about: **the daypart rows overlap and do not add up**, because morning drive is
inside AAA is inside ROS. The response says so in a field rather than in a
comment, because a client building a chart out of this will otherwise stack
them.

The by-week route walks days and rolls up. It does not have a clever query
behind it, and it does not need one: a week is seven boards of a few hundred
rows each and the whole thing is a handful of milliseconds against the numbers
this station will ever see.
"""

from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Query

from ...broadcast import BroadcastWeek
from ...errors import LookupFailure
from ...persistence.logstore import LogRepository
from ...persistence.unitofwork import UnitOfWork
from ...reports import avails as report
from ...services.house import house_from
from ..deps import Work
from ..schemas import Shape
from ..security import Caller, Reader

router = APIRouter(prefix="/avails", tags=["avails"])


class RowOut(Shape):
    label: str
    units: int
    sold: int
    open: int
    sellout: float
    value: str


class DaypartsOut(Shape):
    station: str
    day: date
    #: The rows overlap. Morning drive is inside AAA is inside ROS.
    overlapping: bool = True
    rows: list[RowOut]


class HoursOut(Shape):
    station: str
    day: date
    rows: list[RowOut]


class WeekOut(Shape):
    station: str
    week: str
    first: date
    last: date
    units: int
    sold: int
    open: int
    sellout: float
    days: list[RowOut]


def _row(row: report.Row) -> RowOut:
    return RowOut(
        label=row.label,
        units=row.units,
        sold=row.sold,
        open=row.open,
        sellout=round(row.sellout, 4),
        value=str(row.value.amount),
    )


@router.get("/{station}/{day}", response_model=DaypartsOut)
def by_daypart(
    station: str, day: date, uow: UnitOfWork = Work, caller: Caller = Reader
) -> DaypartsOut:
    """What is open on one day, one row per daypart, narrowest first."""
    house = house_from(uow, day)
    call = house.logging_call(station)
    board = LogRepository(uow.session).board(house.programming[call], day)
    rows = report.by_daypart(board, house.book(call, day))
    return DaypartsOut(station=call, day=day, rows=[_row(row) for row in rows])


@router.get("/{station}/{day}/hours", response_model=HoursOut)
def by_hour(
    station: str,
    day: date,
    thinnest: int = Query(0, ge=0, le=24, description="Only the N thinnest hours"),
    uow: UnitOfWork = Work,
    caller: Caller = Reader,
) -> HoursOut:
    """What is open hour by hour, or only the hours a schedule will jam in."""
    house = house_from(uow, day)
    call = house.logging_call(station)
    board = LogRepository(uow.session).board(house.programming[call], day)
    rows = report.thinnest_hours(board, thinnest) if thinnest else report.by_hour(board)
    return HoursOut(station=call, day=day, rows=[_row(row) for row in rows])


@router.get("/{station}/week/{week_of}", response_model=WeekOut)
def by_week(
    station: str, week_of: date, uow: UnitOfWork = Work, caller: Caller = Reader
) -> WeekOut:
    """One row a day for the broadcast week ``week_of`` falls in."""
    week = BroadcastWeek.of(week_of)
    house = house_from(uow, week.start)
    call = house.logging_call(station)
    grid = house.programming[call]
    logs = LogRepository(uow.session)

    boards = []
    for offset in range(7):
        day = week.start + timedelta(days=offset)
        if not logs.exists(call, day):
            continue
        boards.append(logs.board(grid, day))
    if not boards:
        raise LookupFailure(f"{call} has no logs built for {week}", station=call, week=str(week))
    rows = report.by_day(boards)
    units = sum(row.units for row in rows)
    sold = sum(row.sold for row in rows)
    return WeekOut(
        station=call,
        week=str(week),
        first=week.start,
        last=week.end,
        units=units,
        sold=sold,
        open=units - sold,
        sellout=round(sold / units, 4) if units else 0.0,
        days=[_row(row) for row in rows],
    )
