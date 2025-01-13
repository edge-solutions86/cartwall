"""The calendar the traffic department keeps, which is not the wall calendar.

Radio bills on the broadcast calendar. Weeks run Monday through Sunday, a
broadcast year opens on the Monday of the week that holds January 1, and the
three months inside a quarter run four weeks, four weeks, five weeks. Every
month therefore opens on a Monday and closes on a Sunday, and on the wall
calendar a month boundary lands mid week more often than not. Broadcast August
2025 ends on the 24th.

Two consequences the rest of the package leans on:

* A flight sold "for March" is a whole number of weeks. Nothing downstream
  ever prorates a partial week, which is why the rate card can be quoted per
  week and the invoice run can close a month without asking anybody.
* Roughly one year in six runs 53 weeks. The extra week hangs on December, so
  fourth quarter is 14 weeks in those years. Code that assumes 13 is wrong
  about one year in six, and that is the kind of wrong that shows up as a
  missing invoice rather than as an exception.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date, timedelta

MONDAY = 0
SUNDAY = 6

WEEKS_IN_QUARTER = 13

#: Weeks per month within a quarter. December picks up week 53 when there is
#: one, so this is the pattern before that adjustment, not after it.
MONTH_PATTERN = (4, 4, 5)

_A_WEEK = timedelta(days=7)
_A_DAY = timedelta(days=1)


def week_start(day: date) -> date:
    """The Monday on or before ``day``."""
    return day - timedelta(days=day.weekday())


def year_start(year: int) -> date:
    """The Monday the broadcast year opens on.

    It is the Monday of the wall calendar week holding January 1, so it can sit
    in the previous December. Broadcast 2025 opened on 2024-12-30.
    """
    return week_start(date(year, 1, 1))


def weeks_in_year(year: int) -> int:
    """52 or 53. Anything else means the arithmetic above broke."""
    return (year_start(year + 1) - year_start(year)).days // 7


def _week_counts(year: int) -> tuple[int, ...]:
    """Weeks in each of the twelve months, January first."""
    counts = [MONTH_PATTERN[(month - 1) % 3] for month in range(1, 13)]
    counts[11] += weeks_in_year(year) - 52
    return tuple(counts)


def _month_bounds(year: int, month: int) -> tuple[date, date]:
    counts = _week_counts(year)
    opens = year_start(year) + _A_WEEK * sum(counts[: month - 1])
    return opens, opens + _A_WEEK * counts[month - 1] - _A_DAY


def year_of(day: date) -> int:
    """Which broadcast year ``day`` falls in.

    The last days of December usually belong to the next broadcast year, and
    the first days of January usually belong to the previous one.
    """
    year = day.year
    if day < year_start(year):
        return year - 1
    if day >= year_start(year + 1):
        return year + 1
    return year


@dataclass(frozen=True, slots=True, order=True)
class BroadcastWeek:
    """Monday through Sunday. The unit everything else is built out of."""

    start: date

    def __post_init__(self) -> None:
        if self.start.weekday() != MONDAY:
            raise ValueError(f"a broadcast week starts on a Monday, not a {self.start:%A}")

    @classmethod
    def of(cls, day: date) -> BroadcastWeek:
        return cls(week_start(day))

    @property
    def end(self) -> date:
        return self.start + timedelta(days=6)

    def days(self) -> Iterator[date]:
        for offset in range(7):
            yield self.start + timedelta(days=offset)

    def shift(self, weeks: int) -> BroadcastWeek:
        return BroadcastWeek(self.start + _A_WEEK * weeks)

    def __contains__(self, day: date) -> bool:
        return self.start <= day <= self.end

    def __str__(self) -> str:
        return f"week of {self.start:%Y-%m-%d}"


@dataclass(frozen=True, slots=True, order=True)
class BroadcastMonth:
    """Four or five whole broadcast weeks, named for a wall calendar month."""

    year: int
    month: int

    def __post_init__(self) -> None:
        if not 1 <= self.month <= 12:
            raise ValueError(f"month {self.month} is not a month")

    @classmethod
    def of(cls, day: date) -> BroadcastMonth:
        year = year_of(day)
        for month in range(1, 13):
            if day <= _month_bounds(year, month)[1]:
                return cls(year, month)
        raise AssertionError(f"{day} fell out of broadcast {year}")

    @classmethod
    def parse(cls, text: str) -> BroadcastMonth:
        """``2025-08``, which is how the sales side writes it on an order."""
        year, _, month = text.partition("-")
        if not month:
            raise ValueError(f"{text!r} is not a broadcast month")
        return cls(int(year), int(month))

    @property
    def start(self) -> date:
        return _month_bounds(self.year, self.month)[0]

    @property
    def end(self) -> date:
        return _month_bounds(self.year, self.month)[1]

    @property
    def week_count(self) -> int:
        return _week_counts(self.year)[self.month - 1]

    def weeks(self) -> Iterator[BroadcastWeek]:
        week = BroadcastWeek(self.start)
        for _ in range(self.week_count):
            yield week
            week = week.shift(1)

    def next(self) -> BroadcastMonth:
        if self.month == 12:
            return BroadcastMonth(self.year + 1, 1)
        return BroadcastMonth(self.year, self.month + 1)

    def previous(self) -> BroadcastMonth:
        if self.month == 1:
            return BroadcastMonth(self.year - 1, 12)
        return BroadcastMonth(self.year, self.month - 1)

    def __contains__(self, day: date) -> bool:
        return self.start <= day <= self.end

    def __str__(self) -> str:
        return f"{self.year:04d}-{self.month:02d}"


@dataclass(frozen=True, slots=True, order=True)
class BroadcastQuarter:
    """Thirteen weeks, except the fourth quarter of a 53 week year."""

    year: int
    quarter: int

    def __post_init__(self) -> None:
        if not 1 <= self.quarter <= 4:
            raise ValueError(f"quarter {self.quarter} is not a quarter")

    @classmethod
    def of(cls, day: date) -> BroadcastQuarter:
        month = BroadcastMonth.of(day)
        return cls(month.year, (month.month - 1) // 3 + 1)

    def months(self) -> Iterator[BroadcastMonth]:
        first = (self.quarter - 1) * 3 + 1
        for month in range(first, first + 3):
            yield BroadcastMonth(self.year, month)

    @property
    def start(self) -> date:
        return BroadcastMonth(self.year, (self.quarter - 1) * 3 + 1).start

    @property
    def end(self) -> date:
        return BroadcastMonth(self.year, self.quarter * 3).end

    @property
    def week_count(self) -> int:
        return sum(month.week_count for month in self.months())

    def __str__(self) -> str:
        return f"{self.year:04d}Q{self.quarter}"


def weeks_between(first: date, last: date) -> list[BroadcastWeek]:
    """Every broadcast week touched by the inclusive span ``first`` to ``last``."""
    if last < first:
        raise ValueError("the span runs backwards")
    weeks = []
    week = BroadcastWeek.of(first)
    while week.start <= last:
        weeks.append(week)
        week = week.shift(1)
    return weeks
