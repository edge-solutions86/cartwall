"""The hour clock, which says where the breaks are and how big they get.

Programming draws a clock for each format and traffic lives inside it. The
clock is a pie: music, a break at twenty past, more music, a break at forty
past, the network news at the top. Traffic never decides where a break is. It
decides what goes in one.

Three things a break carries that the log builder has to respect:

* A **position**, measured from the top of the hour. Not a wall clock time,
  because the same clock runs in every hour it is assigned to.
* A **length**, which is what can be sold, and a **unit count**, which is how
  many spots will fit. Those are not the same question. A two minute break
  holds four thirties or two sixties, and a station that sells units rather
  than seconds will refuse the fifth thirty even though eleven seconds are
  free.
* **Protection**. A network break belongs to the network, and the legal ID
  belongs to the licence. Neither will take a local spot however empty it
  looks.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from ..airtime import SECONDS_PER_HOUR, AirTime, Duration, total_length
from ..errors import ValidationError

AN_HOUR = Duration.of_seconds(SECONDS_PER_HOUR)

#: The legal ID has to sit within this much of the top of the hour.
LEGAL_ID_WINDOW = Duration.of_seconds(5 * 60)


class ClockError(ValidationError):
    """A clock that could not be run as drawn."""

    code = "bad_clock"


class BreakKind(Enum):
    LOCAL = "local"
    NETWORK = "network"
    LEGAL_ID = "legal id"
    PROMO = "promo"

    @property
    def sells(self) -> bool:
        """Whether traffic may put a paid spot in it."""
        return self in (BreakKind.LOCAL, BreakKind.PROMO)


@dataclass(frozen=True, slots=True, order=True)
class Break:
    """One stopset, at a fixed offset into whatever hour the clock runs in."""

    position: Duration
    number: int
    kind: BreakKind
    length: Duration
    units: int
    #: Promo breaks sell, but only to house and trade. Paid orders stay out.
    paid: bool = True

    def __post_init__(self) -> None:
        if self.number < 1:
            raise ClockError(f"break {self.number} is not a break number")
        if self.position >= AN_HOUR:
            raise ClockError(f"break {self.number} starts at {self.position}, past the hour")
        if not self.length:
            raise ClockError(f"break {self.number} has no length")
        if self.position + self.length > AN_HOUR:
            raise ClockError(f"break {self.number} runs past the top of the next hour")
        if self.units < 1:
            raise ClockError(f"break {self.number} holds no spots")
        if not self.kind.sells and self.paid:
            raise ClockError(f"break {self.number} is a {self.kind.value} break and cannot be paid")

    @property
    def ends(self) -> Duration:
        return self.position + self.length

    def starts_at(self, hour: int) -> AirTime:
        return AirTime.at(hour) + self.position

    def overlaps(self, other: Break) -> bool:
        return self.position < other.ends and other.position < self.ends

    def __str__(self) -> str:
        return f"break {self.number} at {self.position}"


@dataclass(frozen=True, slots=True)
class Clock:
    """A named hour, drawn once and assigned to many hours of the week."""

    code: str
    name: str
    breaks: tuple[Break, ...]

    def __post_init__(self) -> None:
        if not self.code:
            raise ClockError("a clock needs a code")
        numbers = [brk.number for brk in self.breaks]
        if len(set(numbers)) != len(numbers):
            raise ClockError(f"clock {self.code} numbers a break twice")
        ordered = sorted(self.breaks)
        for earlier, later in zip(ordered, ordered[1:], strict=False):
            if earlier.overlaps(later):
                raise ClockError(
                    f"clock {self.code} has break {earlier.number} running into "
                    f"break {later.number}"
                )
        legal = [brk for brk in self.breaks if brk.kind is BreakKind.LEGAL_ID]
        if len(legal) > 1:
            raise ClockError(f"clock {self.code} calls for two legal IDs")
        for brk in legal:
            near_top = brk.position <= LEGAL_ID_WINDOW or brk.position >= AN_HOUR - LEGAL_ID_WINDOW
            if not near_top:
                raise ClockError(f"clock {self.code} puts the legal ID at {brk.position}")

    @classmethod
    def build(cls, code: str, name: str, breaks: list[Break]) -> Clock:
        return cls(code, name, tuple(sorted(breaks)))

    def numbered(self, number: int) -> Break:
        for brk in self.breaks:
            if brk.number == number:
                return brk
        raise ClockError(f"clock {self.code} has no break {number}")

    @property
    def sellable(self) -> tuple[Break, ...]:
        return tuple(brk for brk in self.breaks if brk.kind.sells and brk.paid)

    @property
    def commercial_length(self) -> Duration:
        return total_length([brk.length for brk in self.sellable])

    @property
    def commercial_minutes(self) -> float:
        return self.commercial_length.seconds / 60

    @property
    def unit_count(self) -> int:
        return sum(brk.units for brk in self.sellable)

    def fits_load(self, minutes: int) -> bool:
        """Whether a station carrying this clock stays inside its stated load."""
        return self.commercial_length <= Duration.of_seconds(minutes * 60)

    def __len__(self) -> int:
        return len(self.breaks)

    def __iter__(self):
        return iter(self.breaks)

    def __str__(self) -> str:
        return f"{self.code} ({self.commercial_minutes:g} minutes)"


def local(number: int, minute: int, seconds: int, units: int) -> Break:
    """Shorthand for the common case, a local stopset on a whole minute."""
    return Break(
        position=Duration.of_seconds(minute * 60),
        number=number,
        kind=BreakKind.LOCAL,
        length=Duration.of_seconds(seconds),
        units=units,
    )


def music_clock() -> Clock:
    """Four stopsets, twelve minutes, legal ID at the top."""
    return Clock.build(
        "MUS",
        "music hour",
        [
            Break(
                position=Duration(0),
                number=1,
                kind=BreakKind.LEGAL_ID,
                length=Duration.of_seconds(10),
                units=1,
                paid=False,
            ),
            local(2, 10, 180, 6),
            local(3, 22, 180, 6),
            local(4, 38, 180, 6),
            local(5, 50, 180, 6),
        ],
    )


def talk_clock() -> Clock:
    """Longer breaks, fewer of them, and the network takes the bottom of the hour."""
    return Clock.build(
        "TLK",
        "news talk hour",
        [
            Break(
                position=Duration(0),
                number=1,
                kind=BreakKind.LEGAL_ID,
                length=Duration.of_seconds(10),
                units=1,
                paid=False,
            ),
            local(2, 8, 240, 8),
            local(3, 20, 240, 8),
            Break(
                position=Duration.of_seconds(30 * 60),
                number=4,
                kind=BreakKind.NETWORK,
                length=Duration.of_seconds(300),
                units=1,
                paid=False,
            ),
            local(5, 42, 240, 8),
            local(6, 52, 180, 6),
        ],
    )
