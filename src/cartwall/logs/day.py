"""The log: one station, one day, every element in the order it hits the air.

The board is what traffic works on. The log is what comes out of it, and they
are not the same document. The board only holds what was sold. The log has to
account for the whole day, including the parts traffic never touches:

* The legal ID at the top of every hour, which is a licence condition and is on
  the log whether anybody sold anything or not.
* Network breaks, which belong to the network and are on the log so the
  operator knows not to fill them.
* The units that did not sell, which are on the log as holes. The automation
  does not care about a hole. The sales manager cares about very little else,
  and the same document has to serve both.

Air times are computed from the clock and the lengths in front, not stored.
That is the only way the log and the board can be guaranteed to agree, and it
means a redub that changes a cut's running time by three tenths moves every
spot behind it, which is exactly what happens on the air.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from ..airtime import AirTime, Duration, total_length
from ..domain.clock import Break, BreakKind
from ..domain.daypart import Weekday
from ..domain.programming import ClockGrid, ProgrammingError
from ..domain.ratecard import ClassOfTime
from ..domain.spot import SpotLength
from ..errors import ValidationError
from ..scheduling.inventory import DayBoard


class LogError(ValidationError):
    """A log that cannot be built or read as asked."""

    code = "bad_log"


class Element(Enum):
    SPOT = "spot"
    LEGAL_ID = "legal id"
    NETWORK = "network"
    PROMO = "promo"
    UNSOLD = "unsold"

    @property
    def airs(self) -> bool:
        """Whether the automation gets handed this line."""
        return self is not Element.UNSOLD


@dataclass(frozen=True, slots=True)
class LogLine:
    """One element of the log, at the second it hits the air."""

    at: AirTime
    break_number: int
    element: Element
    length: Duration
    order: str = ""
    line: int = 0
    advertiser: str = ""
    cart: str = ""
    grade: ClassOfTime | None = None

    @property
    def hour(self) -> int:
        return self.at.hour

    @property
    def is_spot(self) -> bool:
        return self.element is Element.SPOT

    def __str__(self) -> str:
        stamp = f"{self.at.twelve_hour():>11}"
        if self.element is Element.SPOT:
            return f"{stamp}  {self.length!s:>6}  {self.cart or '-':<6} {self.advertiser}"
        return f"{stamp}  {self.length!s:>6}  {self.element.value}"


@dataclass
class DayLog:
    """Everything that will air on one station on one day."""

    station: str
    day: date
    lines: list[LogLine] = field(default_factory=list)

    @classmethod
    def build(cls, grid: ClockGrid, board: DayBoard) -> DayLog:
        if grid.station != board.station:
            raise LogError(f"{grid.station}'s grid was handed {board.station}'s board")
        log = cls(board.station, board.day)
        weekday = Weekday(board.day.weekday())
        for hour in range(24):
            try:
                clock = grid.clock_for(weekday, hour)
            except ProgrammingError:
                continue
            for brk in clock.breaks:
                log.lines.extend(_break_lines(board, hour, brk))
        log.lines.sort(key=lambda entry: (entry.at, entry.break_number))
        return log

    def in_hour(self, hour: int) -> list[LogLine]:
        return [entry for entry in self.lines if entry.hour == hour]

    def in_break(self, hour: int, number: int) -> list[LogLine]:
        return [
            entry for entry in self.lines if entry.hour == hour and entry.break_number == number
        ]

    def spots(self) -> list[LogLine]:
        return [entry for entry in self.lines if entry.is_spot]

    def unsold(self) -> list[LogLine]:
        return [entry for entry in self.lines if entry.element is Element.UNSOLD]

    def airable(self) -> list[LogLine]:
        """What the automation is handed. Holes are not part of it."""
        return [entry for entry in self.lines if entry.element.airs]

    def for_order(self, order: str, line: int | None = None) -> list[LogLine]:
        found = [entry for entry in self.spots() if entry.order == order]
        if line is not None:
            found = [entry for entry in found if entry.line == line]
        return found

    def for_advertiser(self, advertiser: str) -> list[LogLine]:
        return [entry for entry in self.spots() if entry.advertiser == advertiser]

    @property
    def commercial_length(self) -> Duration:
        return total_length([entry.length for entry in self.spots()])

    @property
    def sold_units(self) -> int:
        return sum(_units(entry.length) for entry in self.spots())

    @property
    def open_units(self) -> int:
        return len(self.unsold())

    def hours(self) -> list[int]:
        return sorted({entry.hour for entry in self.lines})

    def legal_ids(self) -> list[LogLine]:
        return [entry for entry in self.lines if entry.element is Element.LEGAL_ID]

    def __len__(self) -> int:
        return len(self.lines)

    def __iter__(self):
        return iter(self.lines)


def _units(length: Duration) -> int:
    return SpotLength(length.whole_seconds).units


_KINDS = {
    BreakKind.LEGAL_ID: Element.LEGAL_ID,
    BreakKind.NETWORK: Element.NETWORK,
    BreakKind.PROMO: Element.PROMO,
}


def _break_lines(board: DayBoard, hour: int, brk: Break) -> list[LogLine]:
    """Every element inside one break, in air order, holes included."""
    starts = AirTime.at(hour) + brk.position
    if not (brk.kind.sells and brk.paid):
        return [
            LogLine(
                at=starts,
                break_number=brk.number,
                element=_KINDS.get(brk.kind, Element.PROMO),
                length=brk.length,
            )
        ]
    avail = board.avail(hour, brk.number)
    lines = []
    for placement, at in avail.air_times():
        lines.append(
            LogLine(
                at=at,
                break_number=brk.number,
                element=Element.SPOT,
                length=placement.length.air,
                order=placement.order,
                line=placement.line,
                advertiser=placement.advertiser,
                cart=placement.cart,
                grade=placement.grade,
            )
        )
    cursor = starts + avail.used_length
    for _ in range(avail.free_units):
        lines.append(
            LogLine(
                at=cursor,
                break_number=brk.number,
                element=Element.UNSOLD,
                length=Duration(0),
            )
        )
    return lines
