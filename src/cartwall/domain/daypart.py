"""Dayparts, which are the unit air is priced and sold in.

A daypart is a set of whole hours on a set of weekdays. Nothing here deals in
part hours, because nothing in the business does: nobody has ever bought
6:30a to 9:15a, and letting a daypart start at half past would put a boundary
inside an hour that the log builder then has to reason about twice.

The week is 168 cells, one per weekday and hour, and a daypart is a subset of
them. That makes the questions the rest of the package asks into set algebra:
does this order's daypart overlap that one, what is left over after the
specific buys are placed, which dayparts is this spot allowed to land in.

Dayparts on a grid may overlap on purpose. Morning drive is inside AAA, and
AAA is inside ROS, and a rate card quotes all three at different money. What a
grid may not have is a hole, because run of schedule is defined as everything
and a hole means some hour is unsellable without anybody deciding that.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import IntEnum

from ..airtime import AirTime
from ..errors import ValidationError

HOURS_IN_DAY = 24
DAYS_IN_WEEK = 7
CELLS_IN_WEEK = HOURS_IN_DAY * DAYS_IN_WEEK

_RANGE = re.compile(r"^([A-Za-z]{1,2})-([A-Za-z]{1,2})$")


class DaypartError(ValidationError):
    """A daypart that could not be sold as described."""

    code = "bad_daypart"


class Weekday(IntEnum):
    MON = 0
    TUE = 1
    WED = 2
    THU = 3
    FRI = 4
    SAT = 5
    SUN = 6

    @property
    def short(self) -> str:
        return _SHORT[self]


_SHORT = {
    Weekday.MON: "M",
    Weekday.TUE: "Tu",
    Weekday.WED: "W",
    Weekday.THU: "Th",
    Weekday.FRI: "F",
    Weekday.SAT: "Sa",
    Weekday.SUN: "Su",
}

_BY_SHORT = {short.upper(): day for day, short in _SHORT.items()}


def parse_days(text: str) -> frozenset[Weekday]:
    """``M-F``, ``Sa``, ``M-Su``, ``M,W,F`` and ``Sa,Su`` are the shapes used.

    A range runs forward through the week and does not wrap, so ``Sa-Su`` is
    the weekend and ``Su-Sa`` is an error rather than the whole week by another
    name.
    """
    cleaned = text.replace(" ", "")
    if not cleaned:
        raise DaypartError("no days given")
    days: set[Weekday] = set()
    for piece in cleaned.split(","):
        span = _RANGE.match(piece)
        if span:
            first, last = (_lookup(part) for part in span.groups())
            if last < first:
                raise DaypartError(f"{piece!r} runs backwards through the week")
            days.update(Weekday(value) for value in range(first, last + 1))
        else:
            days.add(_lookup(piece))
    return frozenset(days)


def _lookup(text: str) -> Weekday:
    try:
        return _BY_SHORT[text.upper()]
    except KeyError:
        raise DaypartError(f"{text!r} is not a day of the week") from None


def show_days(days: frozenset[Weekday]) -> str:
    """The other direction, collapsing runs back into ranges."""
    if not days:
        return ""
    ordered = sorted(days)
    pieces: list[str] = []
    run_start = previous = ordered[0]
    for day in ordered[1:]:
        if day == previous + 1:
            previous = day
            continue
        pieces.append(_show_run(run_start, previous))
        run_start = previous = day
    pieces.append(_show_run(run_start, previous))
    return ",".join(pieces)


def _show_run(first: Weekday, last: Weekday) -> str:
    if first == last:
        return first.short
    if last == first + 1:
        return f"{first.short},{last.short}"
    return f"{first.short}-{last.short}"


@dataclass(frozen=True, slots=True)
class Daypart:
    """Whole hours on whole days, named and sellable."""

    code: str
    name: str
    days: frozenset[Weekday]
    #: Inclusive first hour and exclusive last hour, so 7p to midnight is 19, 24.
    first_hour: int
    last_hour: int

    def __post_init__(self) -> None:
        if not self.code:
            raise DaypartError("a daypart needs a code")
        if not self.days:
            raise DaypartError(f"{self.code} runs on no days")
        if not 0 <= self.first_hour < HOURS_IN_DAY:
            raise DaypartError(f"{self.code} starts at hour {self.first_hour}")
        if not self.first_hour < self.last_hour <= HOURS_IN_DAY:
            raise DaypartError(f"{self.code} ends at hour {self.last_hour}, which is not after it")

    @classmethod
    def build(cls, code: str, name: str, days: str, first_hour: int, last_hour: int) -> Daypart:
        return cls(code, name, parse_days(days), first_hour, last_hour)

    @property
    def hours_a_day(self) -> int:
        return self.last_hour - self.first_hour

    @property
    def cells(self) -> frozenset[tuple[Weekday, int]]:
        return frozenset(
            (day, hour) for day in self.days for hour in range(self.first_hour, self.last_hour)
        )

    def holds(self, day: Weekday, at: AirTime) -> bool:
        return day in self.days and self.first_hour <= at.hour < self.last_hour

    def overlaps(self, other: Daypart) -> bool:
        return bool(self.cells & other.cells)

    def contains_daypart(self, other: Daypart) -> bool:
        return other.cells <= self.cells

    def span(self) -> str:
        return f"{_hour_label(self.first_hour)}-{_hour_label(self.last_hour)}"

    def __str__(self) -> str:
        return f"{show_days(self.days)} {self.span()}"


def _hour_label(hour: int) -> str:
    if hour in (0, 24):
        return "mid"
    if hour == 12:
        return "noon"
    if hour < 12:
        return f"{hour}a"
    return f"{hour - 12}p"


@dataclass(frozen=True, slots=True)
class DaypartGrid:
    """The dayparts one station sells, with run of schedule underneath."""

    dayparts: tuple[Daypart, ...]

    def __post_init__(self) -> None:
        codes = [daypart.code for daypart in self.dayparts]
        repeated = {code for code in codes if codes.count(code) > 1}
        if repeated:
            raise DaypartError(f"{', '.join(sorted(repeated))} is on the grid twice")

    def of(self, code: str) -> Daypart:
        for daypart in self.dayparts:
            if daypart.code == code:
                return daypart
        raise DaypartError(f"{code} is not on this grid")

    def has(self, code: str) -> bool:
        return any(daypart.code == code for daypart in self.dayparts)

    def covering(self, day: Weekday, at: AirTime) -> tuple[Daypart, ...]:
        """Every daypart that instant falls in, narrowest first."""
        found = [daypart for daypart in self.dayparts if daypart.holds(day, at)]
        found.sort(key=lambda daypart: (len(daypart.cells), daypart.code))
        return tuple(found)

    @property
    def covered(self) -> frozenset[tuple[Weekday, int]]:
        cells: set[tuple[Weekday, int]] = set()
        for daypart in self.dayparts:
            cells |= daypart.cells
        return frozenset(cells)

    def gaps(self) -> frozenset[tuple[Weekday, int]]:
        """Hours of the week no daypart sells."""
        everything = {(day, hour) for day in Weekday for hour in range(HOURS_IN_DAY)}
        return frozenset(everything - self.covered)

    def is_whole_week(self) -> bool:
        return not self.gaps()


def standard_grid() -> DaypartGrid:
    """What the music stations run. Sports and farm override pieces of it."""
    return DaypartGrid(
        (
            Daypart.build("AMD", "morning drive", "M-F", 6, 10),
            Daypart.build("MID", "midday", "M-F", 10, 15),
            Daypart.build("PMD", "afternoon drive", "M-F", 15, 19),
            Daypart.build("EVE", "evening", "M-F", 19, 24),
            Daypart.build("SAT", "saturday", "Sa", 6, 24),
            Daypart.build("SUN", "sunday", "Su", 6, 24),
            Daypart.build("OVN", "overnight", "M-Su", 0, 6),
            Daypart.build("AAA", "6a to 7p", "M-Su", 6, 19),
            Daypart.build("ROS", "run of schedule", "M-Su", 0, 24),
        )
    )
