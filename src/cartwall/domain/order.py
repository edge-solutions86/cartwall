"""Orders, the lines on them, and the flights those lines run over.

An **order** is one contract with one advertiser. A **line** is one thing being
bought: a station, a daypart, a class, a length and a rate. A **flight** is
when that line runs and how often.

The count is where this gets interesting. A line is sold as spots per week,
because that is how radio is bought and how a rate card is quoted, but a flight
almost never starts on a Monday. A schedule of twelve a week that starts on a
Thursday has three days of that first week, and three sevenths of twelve is
five and a seventh.

The house rounds those down and says so on the order. Rounding up sells
inventory nobody paid for, and rounding to nearest makes the total depend on
which weekday the client signed, which is impossible to explain over the phone.
Down is defensible: the client gets slightly less in the stub week and the
number on the confirmation is the number that will run.

A line that would round to nothing in its stub week still gets one spot. Zero
in a week the client is paying for is worse than being a spot generous.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from enum import Enum

from ..broadcast import BroadcastWeek, weeks_between
from ..errors import ValidationError
from ..money import Money, total
from .daypart import Weekday, parse_days, show_days
from .ratecard import ClassOfTime
from .spot import SpotLength

DAYS_IN_WEEK = 7


class OrderError(ValidationError):
    """An order that cannot be written as described."""

    code = "bad_order"


class OrderStatus(Enum):
    PENDING = "pending"
    CONFIRMED = "confirmed"
    CANCELLED = "cancelled"

    @property
    def may_schedule(self) -> bool:
        return self is OrderStatus.CONFIRMED


@dataclass(frozen=True, slots=True)
class Flight:
    """When a line runs, on which weekdays, and how many spots a week."""

    first: date
    last: date
    days: frozenset[Weekday]
    per_week: int

    def __post_init__(self) -> None:
        if self.last < self.first:
            raise OrderError(f"a flight from {self.first} to {self.last} runs backwards")
        if not self.days:
            raise OrderError("a flight runs on no days")
        if self.per_week < 1:
            raise OrderError(f"{self.per_week} a week is not a schedule")

    @classmethod
    def build(cls, first: date, last: date, days: str, per_week: int) -> Flight:
        return cls(first, last, parse_days(days), per_week)

    @property
    def weeks(self) -> list[BroadcastWeek]:
        return weeks_between(self.first, self.last)

    def runs_on(self, day: date) -> bool:
        return self.first <= day <= self.last and Weekday(day.weekday()) in self.days

    def air_days(self) -> list[date]:
        """Every date this flight may put a spot on, in order."""
        found = []
        day = self.first
        while day <= self.last:
            if Weekday(day.weekday()) in self.days:
                found.append(day)
            day += timedelta(days=1)
        return found

    def days_in(self, week: BroadcastWeek) -> list[date]:
        return [day for day in week.days() if self.runs_on(day)]

    def full_week_days(self) -> int:
        """How many air days a whole week of this flight has."""
        return len(self.days)

    def count_in(self, week: BroadcastWeek) -> int:
        """Spots ordered in ``week``, prorating a stub week down.

        A whole week gets the full weekly count. A stub week gets that count
        scaled by the air days it actually has, rounded down, floored at one so
        a paid week is never empty.
        """
        available = len(self.days_in(week))
        if available == 0:
            return 0
        if available == self.full_week_days():
            return self.per_week
        return max(1, self.per_week * available // self.full_week_days())

    def weekly_counts(self) -> list[tuple[BroadcastWeek, int]]:
        return [(week, self.count_in(week)) for week in self.weeks]

    @property
    def spots(self) -> int:
        return sum(count for _, count in self.weekly_counts())

    def __str__(self) -> str:
        return f"{self.first:%m/%d}-{self.last:%m/%d} {show_days(self.days)} {self.per_week}x"


@dataclass(frozen=True, slots=True)
class Line:
    """One thing being bought, on one station."""

    number: int
    station: str
    daypart: str
    grade: ClassOfTime
    length: SpotLength
    rate: Money
    flight: Flight
    #: Carts eligible for this line. Empty means production has not dubbed yet.
    carts: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.number < 1:
            raise OrderError(f"line {self.number} is not a line number")
        if not self.station:
            raise OrderError(f"line {self.number} names no station")
        if not self.daypart:
            raise OrderError(f"line {self.number} names no daypart")
        if self.rate.cents < 0:
            raise OrderError(f"line {self.number} is priced at {self.rate}")

    @property
    def spots(self) -> int:
        return self.flight.spots

    @property
    def value(self) -> Money:
        return self.rate * self.spots

    def value_in(self, week: BroadcastWeek) -> Money:
        return self.rate * self.flight.count_in(week)

    @property
    def has_copy(self) -> bool:
        return bool(self.carts)

    def __str__(self) -> str:
        return f"{self.number} {self.station} {self.daypart} {self.length} {self.flight}"


@dataclass(frozen=True, slots=True)
class Order:
    """One contract, with every line on it."""

    number: str
    advertiser: str
    written: date
    salesperson: str
    lines: tuple[Line, ...] = field(default_factory=tuple)
    status: OrderStatus = OrderStatus.PENDING
    agency: str | None = None
    #: What the client calls it. Prints on the confirmation and the invoice.
    campaign: str = ""

    def __post_init__(self) -> None:
        if not self.number:
            raise OrderError("an order needs a number")
        if not self.advertiser:
            raise OrderError(f"order {self.number} names no advertiser")
        numbers = [line.number for line in self.lines]
        repeated = {number for number in numbers if numbers.count(number) > 1}
        if repeated:
            joined = ", ".join(str(number) for number in sorted(repeated))
            raise OrderError(f"order {self.number} numbers line {joined} twice")

    def line(self, number: int) -> Line:
        for line in self.lines:
            if line.number == number:
                return line
        raise OrderError(f"order {self.number} has no line {number}")

    @property
    def spots(self) -> int:
        return sum(line.spots for line in self.lines)

    @property
    def value(self) -> Money:
        return total([line.value for line in self.lines])

    @property
    def first_air(self) -> date:
        if not self.lines:
            raise OrderError(f"order {self.number} has no lines")
        return min(line.flight.first for line in self.lines)

    @property
    def last_air(self) -> date:
        if not self.lines:
            raise OrderError(f"order {self.number} has no lines")
        return max(line.flight.last for line in self.lines)

    def stations(self) -> tuple[str, ...]:
        found: list[str] = []
        for line in self.lines:
            if line.station not in found:
                found.append(line.station)
        return tuple(found)

    def lines_on(self, station: str) -> tuple[Line, ...]:
        return tuple(line for line in self.lines if line.station == station)

    def value_in(self, week: BroadcastWeek) -> Money:
        return total([line.value_in(week) for line in self.lines])

    def confirmed(self) -> Order:
        if self.status is OrderStatus.CANCELLED:
            raise OrderError(f"order {self.number} was cancelled and cannot be confirmed")
        if not self.lines:
            raise OrderError(f"order {self.number} has no lines to confirm")
        return Order(**{**self._fields(), "status": OrderStatus.CONFIRMED})

    def cancelled(self) -> Order:
        return Order(**{**self._fields(), "status": OrderStatus.CANCELLED})

    def with_lines(self, lines: tuple[Line, ...]) -> Order:
        return Order(**{**self._fields(), "lines": lines})

    def _fields(self) -> dict:
        return {
            "number": self.number,
            "advertiser": self.advertiser,
            "written": self.written,
            "salesperson": self.salesperson,
            "lines": self.lines,
            "status": self.status,
            "agency": self.agency,
            "campaign": self.campaign,
        }

    def __len__(self) -> int:
        return len(self.lines)

    def __iter__(self):
        return iter(self.lines)

    def __str__(self) -> str:
        return f"{self.number} {self.advertiser} {self.value}"
