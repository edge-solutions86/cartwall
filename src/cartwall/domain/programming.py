"""Which clock runs in which hour, and what happens when a ballgame takes one.

Drawing a clock and deciding where it runs are two different jobs done by two
different people. Programming draws the clock. The programme director decides
that the music clock runs 6a to 7p weekdays, the talk clock runs 5a to 6a for
the farm block, and that on the eighteen nights a year the high school plays,
the whole evening comes off the grid.

The grid is a stack of assignments rather than a table of hours, because that
is how the decisions actually arrive. A general rule gets laid down once and
exceptions get laid on top of it. The last one filed that covers an hour wins,
which means the order of the stack is load bearing and a grid that is read out
of order will schedule spots into a ballgame.

A **preemption** is an assignment with no clock. The hour still exists, the log
still has to account for it, and there is simply no local inventory in it.
That is not the same as an hour with an empty clock: an empty clock is a
station error, and a preemption is Friday night football.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from ..errors import ValidationError
from .clock import Clock, ClockError
from .daypart import HOURS_IN_DAY, Weekday, parse_days, show_days


class ProgrammingError(ValidationError):
    """A grid that cannot be run."""

    code = "bad_programming"


class Source(Enum):
    """Where an hour's programming comes from."""

    LOCAL = "local"
    NETWORK = "network"
    #: Sport, remote, special. No local avails at all.
    PREEMPTED = "preempted"

    @property
    def sells(self) -> bool:
        return self is not Source.PREEMPTED


@dataclass(frozen=True, slots=True)
class Assignment:
    """One rule about which clock runs where, laid on top of the ones before."""

    label: str
    days: frozenset[Weekday]
    first_hour: int
    last_hour: int
    clock: str = ""
    source: Source = Source.LOCAL

    def __post_init__(self) -> None:
        if not self.label:
            raise ProgrammingError("an assignment needs a label")
        if not self.days:
            raise ProgrammingError(f"{self.label} runs on no days")
        if not 0 <= self.first_hour < self.last_hour <= HOURS_IN_DAY:
            raise ProgrammingError(
                f"{self.label} runs {self.first_hour} to {self.last_hour}, "
                "which is not an hour span"
            )
        if self.source.sells and not self.clock:
            raise ProgrammingError(f"{self.label} sells but names no clock")
        if not self.source.sells and self.clock:
            raise ProgrammingError(f"{self.label} is preempted but names clock {self.clock}")

    @classmethod
    def build(
        cls,
        label: str,
        days: str,
        first_hour: int,
        last_hour: int,
        clock: str = "",
        source: Source = Source.LOCAL,
    ) -> Assignment:
        return cls(label, parse_days(days), first_hour, last_hour, clock, source)

    @classmethod
    def preempt(cls, label: str, days: str, first_hour: int, last_hour: int) -> Assignment:
        return cls.build(label, days, first_hour, last_hour, source=Source.PREEMPTED)

    def covers(self, day: Weekday, hour: int) -> bool:
        return day in self.days and self.first_hour <= hour < self.last_hour

    @property
    def hours(self) -> int:
        return (self.last_hour - self.first_hour) * len(self.days)

    def __str__(self) -> str:
        what = self.clock or self.source.value
        return f"{self.label} {show_days(self.days)} {self.first_hour}-{self.last_hour} {what}"


@dataclass(frozen=True, slots=True)
class Slot:
    """What one hour of one weekday resolves to."""

    day: Weekday
    hour: int
    assignment: Assignment

    @property
    def sells(self) -> bool:
        return self.assignment.source.sells

    @property
    def clock(self) -> str:
        return self.assignment.clock


@dataclass
class ClockGrid:
    """One station's week, in force from a date until the next grid replaces it."""

    station: str
    effective: date
    assignments: list[Assignment] = field(default_factory=list)
    clocks: dict[str, Clock] = field(default_factory=dict)

    def file_clock(self, clock: Clock) -> None:
        if clock.code in self.clocks:
            raise ClockError(f"clock {clock.code} is already filed for {self.station}")
        self.clocks[clock.code] = clock

    def assign(self, assignment: Assignment) -> None:
        if assignment.clock and assignment.clock not in self.clocks:
            raise ProgrammingError(
                f"{assignment.label} names clock {assignment.clock}, which is not filed"
            )
        self.assignments.append(assignment)

    def slot(self, day: Weekday, hour: int) -> Slot:
        """The last assignment filed that covers this hour."""
        if not 0 <= hour < HOURS_IN_DAY:
            raise ProgrammingError(f"hour {hour} is not in the day")
        for assignment in reversed(self.assignments):
            if assignment.covers(day, hour):
                return Slot(day, hour, assignment)
        raise ProgrammingError(f"{self.station} has nothing programmed {day.short} at {hour}")

    def clock_for(self, day: Weekday, hour: int) -> Clock:
        """The clock that runs, or a refusal if the hour is preempted."""
        slot = self.slot(day, hour)
        if not slot.sells:
            raise ProgrammingError(
                f"{self.station} is {slot.assignment.source.value} "
                f"{day.short} at {hour} for {slot.assignment.label}"
            )
        return self.clocks[slot.clock]

    def sells(self, day: Weekday, hour: int) -> bool:
        try:
            return self.slot(day, hour).sells
        except ProgrammingError:
            return False

    def gaps(self) -> list[tuple[Weekday, int]]:
        """Hours nothing has been programmed into."""
        found = []
        for day in Weekday:
            for hour in range(HOURS_IN_DAY):
                if not any(a.covers(day, hour) for a in self.assignments):
                    found.append((day, hour))
        return found

    def is_complete(self) -> bool:
        return not self.gaps()

    def selling_hours(self) -> int:
        return sum(
            1
            for day in Weekday
            for hour in range(HOURS_IN_DAY)
            if self.sells(day, hour)
        )

    def units_a_week(self) -> int:
        """Every local unit the grid puts on the air in a week."""
        total = 0
        for day in Weekday:
            for hour in range(HOURS_IN_DAY):
                if self.sells(day, hour):
                    total += self.clock_for(day, hour).unit_count
        return total
