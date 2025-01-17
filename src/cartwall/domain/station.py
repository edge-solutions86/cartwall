"""The signals we actually put spots on.

Six of them in one Iowa market: two AMs, three FMs and a translator that carries
the HD2 of one of the FMs. They are not interchangeable and traffic cannot treat
them as six copies of the same thing.

The two rules that leak into everything else:

* An AM daytimer signs off at local sunset and cannot carry a spot after that.
  Sunset moves every day, so the last hour a daytimer has to sell moves every
  day, and a flight sold Monday to Friday in December is a different amount of
  inventory than the same flight in June.
* A translator does not have its own log. It carries whatever its parent
  carries, so a spot bought on the translator is really a spot on the parent
  and has to be scheduled there. Selling the pair separately double books the
  same air.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

CALL_SIGN = re.compile(r"^[KW][A-Z]{2,3}(?:-(?:FM|AM|LP))?$")

#: FM sits on odd tenths only, 88.1 through 107.9.
FM_LOW = Decimal("88.1")
FM_HIGH = Decimal("107.9")

#: AM is 10 kHz spaced, 540 through 1700.
AM_LOW = 540
AM_HIGH = 1700
AM_SPACING = 10


class StationError(ValueError):
    """A signal that could not exist as described."""


class Band(Enum):
    AM = "am"
    FM = "fm"
    TRANSLATOR = "translator"

    @property
    def is_fm(self) -> bool:
        return self in (Band.FM, Band.TRANSLATOR)


class Service(Enum):
    """What kind of programming the signal carries, which sets the clock."""

    MUSIC = "music"
    NEWS_TALK = "news talk"
    SPORTS = "sports"
    FARM = "farm"


def _check_frequency(band: Band, frequency: Decimal) -> None:
    if band.is_fm:
        if not FM_LOW <= frequency <= FM_HIGH:
            raise StationError(f"{frequency} is off the FM band")
        tenths = int(frequency * 10) % 10
        if tenths % 2 == 0:
            raise StationError(f"{frequency} is not an FM channel, tenths run odd")
        return
    khz = int(frequency)
    if khz != frequency:
        raise StationError(f"{frequency} is not a whole number of kilohertz")
    if not AM_LOW <= khz <= AM_HIGH:
        raise StationError(f"{khz} is off the AM band")
    if khz % AM_SPACING:
        raise StationError(f"{khz} is not on a 10 kHz channel")


@dataclass(frozen=True, slots=True)
class Station:
    """One signal, with the paperwork that constrains what can air on it."""

    call: str
    band: Band
    frequency: Decimal
    city_of_license: str
    service: Service
    #: Sign off at sunset. AM only, and it changes what inventory exists.
    daytimer: bool = False
    #: Call sign of the signal this one simulcasts. Translators always have one.
    carries: str | None = None
    #: Minutes of commercial matter the format will bear in an hour.
    commercial_load: int = 12

    def __post_init__(self) -> None:
        if not CALL_SIGN.match(self.call):
            raise StationError(f"{self.call!r} is not a call sign")
        _check_frequency(self.band, self.frequency)
        if self.daytimer and self.band is not Band.AM:
            raise StationError(f"{self.call} is not AM, so it cannot be a daytimer")
        if self.band is Band.TRANSLATOR and not self.carries:
            raise StationError(f"{self.call} is a translator with nothing to carry")
        if self.carries == self.call:
            raise StationError(f"{self.call} cannot carry itself")
        if self.commercial_load < 0 or self.commercial_load > 60:
            raise StationError(f"{self.commercial_load} minutes an hour is not a load")

    @property
    def is_satellite(self) -> bool:
        """True when the signal has no log of its own."""
        return self.carries is not None

    @property
    def dial(self) -> str:
        if self.band.is_fm:
            return f"{self.frequency} FM"
        return f"{int(self.frequency)} AM"

    def legal_id(self) -> str:
        """What has to air within a few minutes of the top of every hour."""
        return f"{self.call}, {self.city_of_license}"

    def __str__(self) -> str:
        return self.call


@dataclass(frozen=True, slots=True)
class Cluster:
    """Everything under one licence holder in one market."""

    market: str
    stations: tuple[Station, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        calls = [station.call for station in self.stations]
        duplicated = {call for call in calls if calls.count(call) > 1}
        if duplicated:
            raise StationError(f"{', '.join(sorted(duplicated))} appears twice in the cluster")
        known = set(calls)
        for station in self.stations:
            if station.carries and station.carries not in known:
                raise StationError(
                    f"{station.call} carries {station.carries}, which is not in the cluster"
                )
        for station in self.stations:
            self._walk_to_a_log(station)

    def _walk_to_a_log(self, station: Station) -> Station:
        """Follow the simulcast chain up to the signal that owns the log."""
        seen = [station.call]
        while station.carries:
            if station.carries in seen:
                raise StationError(f"{' carries '.join(seen)} loops back on itself")
            seen.append(station.carries)
            station = next(s for s in self.stations if s.call == station.carries)
        return station

    def station(self, call: str) -> Station:
        for station in self.stations:
            if station.call == call:
                return station
        raise StationError(f"{call} is not one of ours")

    def has(self, call: str) -> bool:
        return any(station.call == call for station in self.stations)

    @property
    def logged(self) -> tuple[Station, ...]:
        """The signals that get a log built for them."""
        return tuple(station for station in self.stations if not station.is_satellite)

    def carried_by(self, call: str) -> tuple[Station, ...]:
        """Signals that simulcast ``call``, directly or through another."""
        found: list[Station] = []
        pending = [call]
        while pending:
            parent = pending.pop()
            for station in self.stations:
                if station.carries == parent and station not in found:
                    found.append(station)
                    pending.append(station.call)
        return tuple(found)

    def airs_on(self, call: str) -> tuple[Station, ...]:
        """Every signal a spot bought on ``call`` actually reaches."""
        parent = self.logging_station(call)
        return (parent, *self.carried_by(parent.call))

    def logging_station(self, call: str) -> Station:
        """The signal whose log a spot bought on ``call`` has to be placed in."""
        return self._walk_to_a_log(self.station(call))

    def __iter__(self):
        return iter(self.stations)

    def __len__(self) -> int:
        return len(self.stations)
