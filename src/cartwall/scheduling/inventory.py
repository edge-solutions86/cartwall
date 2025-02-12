"""What is actually available to sell on one station on one day.

The grid says which clock runs in which hour. This turns that into a real
board: one avail per sellable break per hour of one date, each with the two
capacities a break has, and whatever has already been put in it.

Both capacities are checked on every placement, because either can bind first
and which one does depends on what got sold earlier:

* A three minute break with six units takes six thirties and is full on units
  with nothing left over.
* The same break takes three sixties and is full on seconds with three units
  unused.
* And it takes two sixties and one thirty, which is five units and a hundred
  and fifty seconds, and still has room for one more thirty by both counts.

Spots sit in the break in the order they were placed and the air time of each
one falls out of the lengths in front of it. Nothing here decides what to
place. That is the log builder's job, and it asks these questions constantly.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..airtime import AirTime, Duration, total_length
from ..domain.clock import Break
from ..domain.daypart import Weekday
from ..domain.programming import ClockGrid, ProgrammingError
from ..domain.ratecard import ClassOfTime
from ..domain.spot import SpotLength
from ..errors import ConflictError, ValidationError


class InventoryError(ValidationError):
    """An avail that cannot be built or read as asked."""

    code = "bad_inventory"


class BreakFull(ConflictError):
    """The break will not take another spot of that length."""

    code = "break_full"


@dataclass(frozen=True, slots=True)
class Placement:
    """One spot sitting in one break."""

    order: str
    line: int
    advertiser: str
    length: SpotLength
    grade: ClassOfTime
    cart: str = ""
    #: Pinned by the traffic director. The builder may not move it.
    fixed: bool = False

    def __post_init__(self) -> None:
        if not self.order:
            raise InventoryError("a placement names no order")
        if self.line < 1:
            raise InventoryError(f"order {self.order} line {self.line} is not a line number")

    @property
    def key(self) -> tuple[str, int]:
        return (self.order, self.line)

    def __str__(self) -> str:
        return f"{self.order}-{self.line} {self.advertiser} {self.length}"


@dataclass
class Avail:
    """One break on one date, with what is in it."""

    station: str
    day: date
    hour: int
    number: int
    position: Duration
    capacity: Duration
    units: int
    placements: list[Placement] = field(default_factory=list)

    @classmethod
    def of(cls, station: str, day: date, hour: int, brk: Break) -> Avail:
        return cls(
            station=station,
            day=day,
            hour=hour,
            number=brk.number,
            position=brk.position,
            capacity=brk.length,
            units=brk.units,
        )

    @property
    def starts_at(self) -> AirTime:
        return AirTime.at(self.hour) + self.position

    @property
    def used_units(self) -> int:
        return sum(placement.length.units for placement in self.placements)

    @property
    def used_length(self) -> Duration:
        return total_length([placement.length.air for placement in self.placements])

    @property
    def free_units(self) -> int:
        return self.units - self.used_units

    @property
    def free_length(self) -> Duration:
        return self.capacity - self.used_length

    @property
    def is_full(self) -> bool:
        return self.free_units <= 0 or not self.free_length

    def accepts(self, length: SpotLength) -> bool:
        return length.units <= self.free_units and length.air <= self.free_length

    def holds(self, order: str, line: int) -> bool:
        return any(placement.key == (order, line) for placement in self.placements)

    def add(self, placement: Placement) -> None:
        if not self.accepts(placement.length):
            raise BreakFull(
                f"{self.station} {self.day} hour {self.hour} break {self.number} "
                f"will not take a {placement.length}",
                station=self.station,
                hour=self.hour,
                number=self.number,
                free_units=self.free_units,
                free_seconds=self.free_length.seconds,
            )
        self.placements.append(placement)

    def drop(self, order: str, line: int) -> Placement:
        for index, placement in enumerate(self.placements):
            if placement.key == (order, line):
                return self.placements.pop(index)
        raise InventoryError(
            f"{order}-{line} is not in {self.station} hour {self.hour} break {self.number}"
        )

    def air_times(self) -> list[tuple[Placement, AirTime]]:
        """Each spot with the time it will hit the air, in break order."""
        cursor = self.starts_at
        timed = []
        for placement in self.placements:
            timed.append((placement, cursor))
            cursor = cursor + placement.length.air
        return timed

    def __len__(self) -> int:
        return len(self.placements)

    def __str__(self) -> str:
        return f"{self.station} {self.day} {self.starts_at} break {self.number}"


@dataclass
class DayBoard:
    """Every avail one station has on one date."""

    station: str
    day: date
    avails: list[Avail] = field(default_factory=list)

    @classmethod
    def build(cls, grid: ClockGrid, day: date) -> DayBoard:
        """Lay out a date from the grid, skipping preempted hours."""
        board = cls(grid.station, day)
        weekday = Weekday(day.weekday())
        for hour in range(24):
            try:
                clock = grid.clock_for(weekday, hour)
            except ProgrammingError:
                continue
            for brk in clock.sellable:
                board.avails.append(Avail.of(grid.station, day, hour, brk))
        return board

    def avail(self, hour: int, number: int) -> Avail:
        for avail in self.avails:
            if avail.hour == hour and avail.number == number:
                return avail
        raise InventoryError(f"{self.station} has no break {number} in hour {hour} on {self.day}")

    def in_hour(self, hour: int) -> list[Avail]:
        return [avail for avail in self.avails if avail.hour == hour]

    def hours(self) -> list[int]:
        return sorted({avail.hour for avail in self.avails})

    def open_avails(self, length: SpotLength) -> list[Avail]:
        """Every avail that would take a spot of this length, in air order."""
        found = [avail for avail in self.avails if avail.accepts(length)]
        found.sort(key=lambda avail: (avail.hour, avail.position))
        return found

    @property
    def total_units(self) -> int:
        return sum(avail.units for avail in self.avails)

    @property
    def used_units(self) -> int:
        return sum(avail.used_units for avail in self.avails)

    @property
    def free_units(self) -> int:
        return self.total_units - self.used_units

    def sellout(self) -> float:
        """Fraction of the day's units that are spoken for, zero to one."""
        if not self.total_units:
            return 0.0
        return self.used_units / self.total_units

    def placements(self) -> list[tuple[Placement, AirTime]]:
        """Everything on the board, in the order it will air."""
        timed: list[tuple[Placement, AirTime]] = []
        for avail in sorted(self.avails, key=lambda avail: (avail.hour, avail.position)):
            timed.extend(avail.air_times())
        return timed

    def find(self, order: str, line: int) -> list[Avail]:
        return [avail for avail in self.avails if avail.holds(order, line)]

    def count_for(self, order: str, line: int) -> int:
        return sum(
            1
            for avail in self.avails
            for placement in avail.placements
            if placement.key == (order, line)
        )

    def __len__(self) -> int:
        return len(self.avails)
