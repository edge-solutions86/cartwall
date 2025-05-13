"""Writing the as-run back onto the log, so billing has one place to read.

The reconciliation is a comparison. Posting is what makes it durable, and it
writes onto the spot rows rather than into a table of its own.

That is the opposite of the decision made for bumps, and the reason is worth
being explicit about. A bump is a debt: it is about a spot that is no longer
there, it outlives the log it came off, and it has a life of its own until
somebody makes it good. What actually aired is a property of the spot itself.
It has no life apart from the row, it is read every single time the row is,
and putting it in a second table means every billing query is a join that
somebody will eventually forget to write.

Posting is idempotent. Running it twice on the same day gives the same answer,
because it sets every field from the reconciliation rather than adding to what
is there. A day that gets a corrected as-run at four in the afternoon is
posted again and the second answer wins.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..errors import ConflictError
from ..logs.approval import LogState
from ..persistence.models.logs import LogRow, SpotRow
from ..playout.asrun import Played
from .matcher import Reconciliation


class NotAired(ConflictError):
    """A day that has not gone out cannot have an as-run posted against it."""

    code = "not_aired"


@dataclass
class Posting:
    """What one posting run changed."""

    station: str
    day: date
    aired: int = 0
    missed: int = 0
    moved: int = 0
    unmatched: int = 0

    @property
    def touched(self) -> int:
        return self.aired + self.missed

    def __str__(self) -> str:
        return (
            f"{self.station} {self.day}: {self.aired} aired, {self.missed} missed, "
            f"{self.moved} moved, {self.unmatched} not on the log"
        )


def post(session: Session, result: Reconciliation, force: bool = False) -> Posting:
    """Write a reconciliation onto the stored log for that day."""
    row = session.scalars(
        select(LogRow).where(LogRow.station == result.station, LogRow.day == result.day)
    ).first()
    if row is None:
        raise NotAired(
            f"{result.station} has no log for {result.day}",
            station=result.station,
            day=str(result.day),
        )
    if row.state is not LogState.AIRED and not force:
        raise NotAired(
            f"{result.station} {result.day} is {row.state.value}, not aired",
            station=result.station,
            day=str(result.day),
            state=row.state.value,
        )

    spots = {
        (spot.order_number, spot.line, spot.hour, spot.break_number): spot for spot in row.spots
    }
    report = Posting(result.station, result.day)

    for spot in row.spots:
        spot.aired = False
        spot.aired_at = None
        spot.aired_length = None
        spot.aired_status = Played.UNKNOWN

    for match in result.matched:
        entry = match.scheduled
        found = spots.get((entry.order, entry.line, entry.hour, entry.break_number))
        if found is None:
            continue
        spot = found
        spot.aired = match.billable
        spot.aired_at = match.aired.at
        spot.aired_length = match.aired.length
        spot.aired_status = match.aired.status
        if match.billable:
            report.aired += 1
        if match.moved:
            report.moved += 1

    for miss in result.missed:
        entry = miss.scheduled
        found = spots.get((entry.order, entry.line, entry.hour, entry.break_number))
        if found is not None:
            found.aired_status = _status_named(miss.reason)
        report.missed += 1

    report.unmatched = len(result.extra)
    session.flush()
    return report


def _status_named(reason: str) -> Played:
    for status in Played:
        if status.value == reason:
            return status
    return Played.UNKNOWN


def aired_spots(session: Session, order: str, first: date, last: date) -> list[SpotRow]:
    """Every spot of one order that actually went out, in air order."""
    return list(
        session.scalars(
            select(SpotRow)
            .join(LogRow)
            .where(
                SpotRow.order_number == order,
                SpotRow.aired.is_(True),
                LogRow.day >= first,
                LogRow.day <= last,
            )
            .order_by(LogRow.day, SpotRow.at)
        ).all()
    )


def missed_spots(session: Session, order: str, first: date, last: date) -> list[SpotRow]:
    """Every spot of one order that did not, on days that have aired."""
    return list(
        session.scalars(
            select(SpotRow)
            .join(LogRow)
            .where(
                SpotRow.order_number == order,
                SpotRow.aired.is_(False),
                LogRow.state == LogState.AIRED,
                LogRow.day >= first,
                LogRow.day <= last,
            )
            .order_by(LogRow.day, SpotRow.at)
        ).all()
    )
