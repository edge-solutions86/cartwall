"""Gathering what is owed from the three places it comes from.

The makegood ledger does not know how a debt arose and should not. This is the
adapter that reads the three sources and puts them on one ledger:

* **Spots that did not run.** Posted as-run rows on a day that has aired, where
  the spot is marked not aired. Only aired days count: a day whose as-run has
  not been posted has not failed to run anything, it has simply not been read.
* **Spots that were bumped.** The bump table, where nothing has been made good
  yet. These are already known to be owed, which is why they were written down.
* **Spots that were never scheduled.** The gap between what a line's flight
  asked for in a week and what the log actually carries. This is the one that
  gets forgotten, because nothing failed: the builder ran out of room, said so
  in its report, and the report was read by nobody a fortnight later.

The rate on a debt comes off the order line, not off the rate card. What is
owed is what the client paid for, and the card may have moved since.

Only confirmed orders are gathered. A cancelled order owes nothing, however
much of it did not run.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import select

from ..broadcast import BroadcastWeek, weeks_between
from ..errors import ValidationError
from ..logs.approval import LogState
from ..persistence.models.logs import BumpRow, LogRow, SpotRow
from ..persistence.unitofwork import UnitOfWork
from .makegood import Cause, Debt, Ledger


class OwingError(ValidationError):
    """A gather that cannot be run as asked."""

    code = "bad_owing"


def _line_of(uow: UnitOfWork, orders: dict, number: str, line: int):
    if number not in orders:
        try:
            orders[number] = uow.orders.get(number)
        except Exception:
            orders[number] = None
    order = orders[number]
    if order is None or not order.status.may_schedule:
        return None, None
    try:
        return order, order.line(line)
    except Exception:
        return order, None


def did_not_run(uow: UnitOfWork, first: date, last: date) -> list[Debt]:
    """Spots the as-run says did not go out, on days that have aired."""
    rows = uow.session.scalars(
        select(SpotRow)
        .join(LogRow)
        .where(
            SpotRow.aired.is_(False),
            LogRow.state == LogState.AIRED,
            LogRow.day >= first,
            LogRow.day <= last,
        )
        .order_by(LogRow.day, SpotRow.at)
    ).all()
    orders: dict = {}
    found = []
    for row in rows:
        order, line = _line_of(uow, orders, row.order_number, row.line)
        if line is None:
            continue
        found.append(
            Debt(
                order=row.order_number,
                line=row.line,
                advertiser=row.advertiser,
                station=row.log.station,
                daypart=line.daypart,
                grade=row.grade,
                length=row.length,
                rate=line.rate,
                day=row.log.day,
                cause=Cause.DID_NOT_RUN,
            )
        )
    return found


def bumped(uow: UnitOfWork, first: date, last: date) -> list[Debt]:
    """Spots a higher class took the unit from, not yet made good."""
    rows = uow.session.scalars(
        select(BumpRow)
        .where(
            BumpRow.made_good.is_(False),
            BumpRow.day >= first,
            BumpRow.day <= last,
        )
        .order_by(BumpRow.day, BumpRow.hour, BumpRow.break_number)
    ).all()
    orders: dict = {}
    found = []
    for row in rows:
        order, line = _line_of(uow, orders, row.order_number, row.line)
        if line is None:
            continue
        found.append(
            Debt(
                order=row.order_number,
                line=row.line,
                advertiser=row.advertiser,
                station=row.station,
                daypart=line.daypart,
                grade=row.grade,
                length=row.length,
                rate=line.rate,
                day=row.day,
                cause=Cause.BUMPED,
            )
        )
    return found


def never_scheduled(uow: UnitOfWork, week: BroadcastWeek) -> list[Debt]:
    """The gap between what a line asked for in a week and what is on the log."""
    found = []
    for order in uow.orders.running_on(week.start) + uow.orders.running_on(week.end):
        if not order.status.may_schedule:
            continue
        for line in order.lines:
            wanted = line.flight.count_in(week)
            if not wanted:
                continue
            carried = _scheduled(uow, order.number, line.number, week)
            for _ in range(max(wanted - carried, 0)):
                found.append(
                    Debt(
                        order=order.number,
                        line=line.number,
                        advertiser=order.advertiser,
                        station=line.station,
                        daypart=line.daypart,
                        grade=line.grade,
                        length=line.length,
                        rate=line.rate,
                        day=week.start,
                        cause=Cause.NEVER_SCHEDULED,
                    )
                )
    return found


def _scheduled(uow: UnitOfWork, order: str, line: int, week: BroadcastWeek) -> int:
    rows = uow.session.scalars(
        select(SpotRow.id)
        .join(LogRow)
        .where(
            SpotRow.order_number == order,
            SpotRow.line == line,
            LogRow.day >= week.start,
            LogRow.day <= week.end,
        )
    ).all()
    return len(rows)


def gather(uow: UnitOfWork, first: date, last: date, scheduled: bool = True) -> Ledger:
    """Every debt in a span, from every source, on one ledger.

    ``scheduled`` turns off the never scheduled pass, which is the expensive
    one. The nightly report wants it. A quick look at last night does not.
    """
    if last < first:
        raise OwingError("the span runs backwards")
    ledger = Ledger()
    for debt in did_not_run(uow, first, last):
        ledger.owe(debt)
    for debt in bumped(uow, first, last):
        ledger.owe(debt)
    if scheduled:
        seen = set()
        for week in weeks_between(first, last):
            if week.start in seen:
                continue
            seen.add(week.start)
            for debt in never_scheduled(uow, week):
                ledger.owe(debt)
    return ledger
