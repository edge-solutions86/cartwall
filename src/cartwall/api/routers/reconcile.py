"""Posting the as-run, and reading back what the comparison found.

The as-run arrives as the automation wrote it, which is a csv file, so this is
the one router that takes a body of text rather than json. Wrapping the file in
a json envelope would mean whoever runs the nightly job has to escape a few
thousand lines of csv, and the first thing they would do is get the quoting
wrong at two in the morning.

Two routes run the same comparison and only one of them writes:

* ``POST /reconcile/{station}/{day}/check`` compares and answers. Nothing moves.
* ``POST /reconcile/{station}/{day}`` compares, then writes the result onto the
  stored spots so billing can see it.

Keeping the dry run is not politeness. Posting is the step that decides what a
client is charged for, and the traffic director wants to look at the
discrepancies before the numbers become an invoice. That matters most on the
day the automation was upgraded and quietly stopped echoing references back,
because then every match comes off the cart pass and the drift column is the
only sign anything is wrong.

The read route goes the other way. It does not touch the as-run at all. It
answers off what was already posted, because by the time somebody asks why a
line is short the file is a week old and on somebody else's laptop.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query, Request

from ...errors import LookupFailure
from ...logs.day import DayLog
from ...persistence.logstore import LogRepository
from ...persistence.models.logs import SpotRow
from ...persistence.unitofwork import UnitOfWork
from ...playout import asrun
from ...playout.asrun import AsRunError, Played
from ...reconcile.matcher import Match, Reconciliation, reconcile
from ...reconcile.posting import aired_spots, missed_spots, post
from ...services.audit import try_record
from ...services.house import grid_for
from ..deps import Work
from ..schemas import Shape
from ..security import Caller, Manager, Reader, Traffic

router = APIRouter(prefix="/reconcile", tags=["reconcile"])


async def read_body(request: Request) -> str:
    """The raw request body, decoded, without going near a json parser.

    ``Body()`` will not do here. Annotating a handler argument as ``str`` makes
    fastapi read the body as json and hand back the string inside it, so a csv
    file posted straight up is rejected before the handler ever runs. The body
    has to be taken off the request itself.
    """
    raw = await request.body()
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        raise AsRunError("the as-run is not utf-8 text") from None


#: The as-run comes in as the automation wrote it, so the body is text.
AsRunBody = Depends(read_body)


class MatchOut(Shape):
    order: str
    line: int
    cart: str
    scheduled_at: str
    aired_at: str
    #: How far it moved. Zero means it ran where it was sold.
    drift_seconds: int
    on_time: bool
    short: bool
    status: Played
    #: True when the automation echoed our order and line back.
    by_reference: bool


class MissedOut(Shape):
    order: str
    line: int
    cart: str
    scheduled_at: str
    reason: str


class ExtraOut(Shape):
    cart: str
    title: str
    aired_at: str
    status: Played


class OwedOut(Shape):
    order: str
    line: int
    spots: int


class CheckOut(Shape):
    station: str
    day: date
    scheduled: int
    aired: int
    clean: bool
    matched: list[MatchOut]
    missed: list[MissedOut]
    extra: list[ExtraOut]
    #: What each line is short by, worst first.
    owed: list[OwedOut]


class PostOut(Shape):
    station: str
    day: date
    aired: int
    missed: int
    moved: int
    #: As-run lines that answered to nothing on the log.
    unlogged: int


class SpotOut(Shape):
    order: str
    line: int
    advertiser: str
    cart: str
    hour: int
    scheduled_at: str
    aired_at: str
    status: Played


def _compare(uow: UnitOfWork, station: str, day: date, text: str) -> Reconciliation:
    """The shared half. Read the log, read the file, put them side by side."""
    grid = grid_for(uow, station, day)
    logs = LogRepository(uow.session)
    if not logs.exists(grid.station, day):
        raise LookupFailure(
            f"{grid.station} has no log for {day}", station=grid.station, day=str(day)
        )
    built = DayLog.build(grid, logs.board(grid, day))
    return reconcile(built, asrun.parse(text))


def _match_out(match: Match) -> MatchOut:
    return MatchOut(
        order=match.scheduled.order,
        line=match.scheduled.line,
        cart=match.aired.cart,
        scheduled_at=match.scheduled.at.twelve_hour(),
        aired_at=match.aired.at.twelve_hour(),
        drift_seconds=match.drift.tenths // 10,
        on_time=match.on_time,
        short=match.short,
        status=match.aired.status,
        by_reference=match.by_reference,
    )


def _check_out(result: Reconciliation) -> CheckOut:
    return CheckOut(
        station=result.station,
        day=result.day,
        scheduled=result.scheduled,
        aired=result.aired,
        clean=result.is_clean,
        matched=[_match_out(match) for match in result.matched],
        missed=[
            MissedOut(
                order=miss.scheduled.order,
                line=miss.scheduled.line,
                cart=miss.scheduled.cart,
                scheduled_at=miss.scheduled.at.twelve_hour(),
                reason=miss.reason,
            )
            for miss in result.missed
        ],
        extra=[
            ExtraOut(
                cart=extra.aired.cart,
                title=extra.aired.title,
                aired_at=extra.aired.at.twelve_hour(),
                status=extra.aired.status,
            )
            for extra in result.extra
        ],
        owed=[
            OwedOut(order=order, line=line, spots=count)
            for (order, line), count in result.owed().items()
        ],
    )


@router.post("/{station}/{day}/check", response_model=CheckOut)
def check(
    station: str,
    day: date,
    text: str = AsRunBody,
    uow: UnitOfWork = Work,
    caller: Caller = Traffic,
) -> CheckOut:
    """Compare the file against the log and answer. Nothing is written."""
    return _check_out(_compare(uow, station, day, text))


@router.post("/{station}/{day}", response_model=PostOut)
def post_asrun(
    station: str,
    day: date,
    text: str = AsRunBody,
    force: bool = Query(False, description="Post against a day that has not aired"),
    uow: UnitOfWork = Work,
    caller: Caller = Traffic,
) -> PostOut:
    """Compare, then write the result onto the stored spots."""
    result = _compare(uow, station, day, text)
    report = post(uow.session, result, force=force)
    try_record(
        uow.session,
        "asrun.posted",
        caller.name,
        "log",
        f"{result.station} {day}",
        aired=report.aired,
        missed=report.missed,
        moved=report.moved,
        forced=force,
    )
    return PostOut(
        station=report.station,
        day=report.day,
        aired=report.aired,
        missed=report.missed,
        moved=report.moved,
        unlogged=report.unmatched,
    )


@router.post("/{station}/{day}/aired", response_model=PostOut)
def mark_aired(
    station: str, day: date, uow: UnitOfWork = Work, caller: Caller = Manager
) -> PostOut:
    """Say the day went out. Until it has, an as-run against it is refused."""
    grid = grid_for(uow, station, day)
    status = LogRepository(uow.session).mark_aired(grid.station, day)
    try_record(
        uow.session,
        "log.aired",
        caller.name,
        "log",
        f"{grid.station} {day}",
        revision=status.revision,
    )
    return PostOut(station=grid.station, day=day, aired=0, missed=0, moved=0, unlogged=0)


def _spot_out(spot: SpotRow) -> SpotOut:
    return SpotOut(
        order=spot.order_number,
        line=spot.line,
        advertiser=spot.advertiser,
        cart=spot.cart,
        hour=spot.hour,
        scheduled_at=spot.at.twelve_hour(),
        aired_at=spot.aired_at.twelve_hour() if spot.aired_at else "",
        status=spot.aired_status,
    )


@router.get("/order/{order}", response_model=list[SpotOut])
def read_order(
    order: str,
    first: date = Query(description="First day of the span"),
    last: date = Query(description="Last day of the span"),
    aired: bool = Query(True, description="Aired spots, or the ones that were missed"),
    uow: UnitOfWork = Work,
    caller: Caller = Reader,
) -> list[SpotOut]:
    """What one order actually did over a span, off what was posted."""
    if last < first:
        raise LookupFailure(f"{last} is before {first}", first=str(first), last=str(last))
    found = (
        aired_spots(uow.session, order, first, last)
        if aired
        else missed_spots(uow.session, order, first, last)
    )
    return [_spot_out(spot) for spot in found]
