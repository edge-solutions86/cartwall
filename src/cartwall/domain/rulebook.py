"""One station's filed tables, gathered so the rest of the package asks once.

Every table in this package is filed per station and most of them are dated.
Without something to pull them together, the log builder ends up carrying six
arguments and answering the question "which card was in force" in three places
that will eventually disagree.

A rule book is that gathering. It is built for one station and one date, it is
read only once it exists, and the date it was built for is the date every
lookup inside it uses. Building a new one is cheap. Reaching into an old one
for a different date is the mistake this class exists to prevent, which is why
nothing here takes a date argument.

Nothing is defaulted. A station with no separation rules filed gets an empty
book and places spots beside anything, and that is a decision somebody has to
have made rather than a thing that quietly happened.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ..airtime import AirTime
from ..errors import ValidationError
from .account import AccountBook
from .clock import Clock
from .daypart import DaypartGrid, Weekday
from .programming import ClockGrid
from .ratecard import ClassOfTime, RateCard, RateCardBook
from .separation import SeparationBook
from .spot import SpotLength
from .station import Cluster, Station


class RuleBookError(ValidationError):
    """A station that cannot be traded on the day asked about."""

    code = "bad_rulebook"


@dataclass(frozen=True, slots=True)
class RuleBook:
    """Everything one station's traffic needs, pinned to one date."""

    station: Station
    on: date
    dayparts: DaypartGrid
    programming: ClockGrid
    separation: SeparationBook
    card: RateCard

    def __post_init__(self) -> None:
        if self.programming.station != self.station.call:
            raise RuleBookError(
                f"{self.station.call} was handed {self.programming.station}'s programming"
            )
        if self.card.station != self.station.call:
            raise RuleBookError(f"{self.station.call} was handed {self.card.station}'s rate card")
        if self.station.is_satellite:
            raise RuleBookError(
                f"{self.station.call} carries {self.station.carries} and has no log of its own"
            )

    @property
    def call(self) -> str:
        return self.station.call

    def clock(self, hour: int) -> Clock:
        return self.programming.clock_for(Weekday(self.on.weekday()), hour)

    def sells(self, hour: int) -> bool:
        return self.programming.sells(Weekday(self.on.weekday()), hour)

    def selling_hours(self) -> list[int]:
        return [hour for hour in range(24) if self.sells(hour)]

    def dayparts_for(self, hour: int) -> tuple[str, ...]:
        """Daypart codes covering this hour, narrowest first."""
        weekday = Weekday(self.on.weekday())
        return tuple(
            daypart.code for daypart in self.dayparts.covering(weekday, AirTime.at(hour))
        )

    def rate(self, daypart: str, grade: ClassOfTime, length: SpotLength):
        return self.card.rate(daypart, grade, length)

    def units_available(self) -> int:
        return sum(self.clock(hour).unit_count for hour in self.selling_hours())

    def commercial_minutes(self) -> float:
        return sum(self.clock(hour).commercial_minutes for hour in self.selling_hours())

    def over_load(self) -> list[int]:
        """Hours whose clock carries more than the station says it will."""
        return [
            hour
            for hour in self.selling_hours()
            if not self.clock(hour).fits_load(self.station.commercial_load)
        ]


@dataclass
class House:
    """The whole cluster's filed tables, and the maker of rule books."""

    cluster: Cluster
    accounts: AccountBook
    cards: RateCardBook
    dayparts: dict[str, DaypartGrid]
    programming: dict[str, ClockGrid]
    separation: dict[str, SeparationBook]

    def book(self, call: str, on: date) -> RuleBook:
        """The rule book for one station on one date."""
        station = self.cluster.station(call)
        if station.is_satellite:
            raise RuleBookError(
                f"{call} carries {station.carries}, ask for that station's book instead"
            )
        if call not in self.dayparts:
            raise RuleBookError(f"{call} has no daypart grid filed")
        if call not in self.programming:
            raise RuleBookError(f"{call} has no programming grid filed")
        dayparts = self.dayparts[call]
        programming = self.programming[call]
        return RuleBook(
            station=station,
            on=on,
            dayparts=dayparts,
            programming=programming,
            separation=self.separation.get(call, SeparationBook()),
            card=self.cards.in_force(call, on),
        )

    def books(self, on: date) -> list[RuleBook]:
        """Every station that gets a log built on ``on``."""
        return [self.book(station.call, on) for station in self.cluster.logged]

    def logging_call(self, call: str) -> str:
        """Where a spot bought on ``call`` actually has to be scheduled."""
        return self.cluster.logging_station(call).call
