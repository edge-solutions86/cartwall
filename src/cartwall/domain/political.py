"""Political advertising, where the rate is decided by what everybody else paid.

Inside the window before an election, a legally qualified candidate has to be
charged the **lowest unit charge**: the lowest rate the station actually
charged any advertiser for the same class and amount of time in the same
period. Not the lowest on the card. The lowest anybody paid.

That makes this the one rate in the system that is not a lookup. It is a
measurement of every other order on the books, taken at a moment, and it moves
when any of them moves. A discount given to a furniture store in October
lowers what a candidate pays for the same daypart, and if it is given after
the candidate's invoice went out the station owes a rebate.

The windows are fixed and they are not the same length:

* **45 days** before a primary or a primary runoff.
* **60 days** before a general or a special election.

Outside the window a candidate is a normal advertiser at normal rates. Issue
advertising is not entitled to the lowest unit charge at all, ever, however
political it sounds. Only a legally qualified candidate's own authorised
committee is, and only for a use of the candidate's own voice or image.

Everything here is about **charge**, not about **rate card**. A class of time
that nobody bought has no lowest unit charge, because nothing was charged.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from enum import Enum

from ..errors import ValidationError
from ..money import Money
from .ratecard import ClassOfTime
from .spot import SpotLength

#: Days before a primary during which the lowest unit charge applies.
PRIMARY_WINDOW = 45

#: Days before a general or special election.
GENERAL_WINDOW = 60


class PoliticalError(ValidationError):
    """A political order that cannot be taken as described."""

    code = "bad_political"


class Election(Enum):
    PRIMARY = "primary"
    RUNOFF = "runoff"
    GENERAL = "general"
    SPECIAL = "special"

    @property
    def window_days(self) -> int:
        if self in (Election.PRIMARY, Election.RUNOFF):
            return PRIMARY_WINDOW
        return GENERAL_WINDOW


class Use(Enum):
    """What the spot is, which decides whether the charge rule applies."""

    #: The candidate's own voice or image, placed by their committee.
    CANDIDATE = "candidate"
    #: Somebody else's advertising about an issue or a candidate.
    ISSUE = "issue"

    @property
    def entitled(self) -> bool:
        return self is Use.CANDIDATE


@dataclass(frozen=True, slots=True)
class Race:
    """One election, on one date, with the window it opens."""

    office: str
    kind: Election
    election_day: date

    def __post_init__(self) -> None:
        if not self.office:
            raise PoliticalError("a race needs an office")

    @property
    def window_opens(self) -> date:
        return self.election_day - timedelta(days=self.kind.window_days)

    def in_window(self, day: date) -> bool:
        """Inclusive at both ends. Election day itself is inside it."""
        return self.window_opens <= day <= self.election_day

    def days_left(self, day: date) -> int:
        return (self.election_day - day).days

    def __str__(self) -> str:
        return f"{self.office} {self.kind.value} {self.election_day:%m/%d/%Y}"


@dataclass(frozen=True, slots=True)
class Candidacy:
    """A legally qualified candidate and the committee that pays."""

    candidate: str
    race: Race
    committee: str
    #: The disclaimer that has to be in the copy. Kept so it can be checked.
    authorised_by: str = ""

    def __post_init__(self) -> None:
        if not self.candidate:
            raise PoliticalError("a candidacy needs a candidate")
        if not self.committee:
            raise PoliticalError(f"{self.candidate} names no committee")

    def entitled_on(self, day: date, use: Use) -> bool:
        """Whether the lowest unit charge applies to a spot on this day."""
        return use.entitled and self.race.in_window(day)

    def __str__(self) -> str:
        return f"{self.candidate} for {self.race.office}"


@dataclass(frozen=True, slots=True)
class Charge:
    """One thing somebody actually paid, which is what the rule measures."""

    station: str
    daypart: str
    grade: ClassOfTime
    length: SpotLength
    rate: Money
    day: date
    advertiser: str = ""

    def comparable_to(self, other: Charge) -> bool:
        """Same class and same amount of time on the same station."""
        return (
            self.station == other.station
            and self.daypart == other.daypart
            and self.grade is other.grade
            and self.length == other.length
        )

    def __str__(self) -> str:
        return f"{self.station} {self.daypart} {self.grade.value} {self.length} {self.rate}"


def lowest_unit_charge(
    charges: list[Charge],
    station: str,
    daypart: str,
    grade: ClassOfTime,
    length: SpotLength,
    first: date,
    last: date,
) -> Money | None:
    """The lowest rate anybody was charged for this class in this window.

    None when nothing comparable was charged. That is not zero and it is not
    the card: a class nobody bought has no lowest unit charge, and the station
    quotes its card rate with a note saying why.
    """
    if last < first:
        raise PoliticalError("the window runs backwards")
    matching = [
        charge.rate
        for charge in charges
        if charge.station == station
        and charge.daypart == daypart
        and charge.grade is grade
        and charge.length == length
        and first <= charge.day <= last
    ]
    return min(matching) if matching else None


def owed_back(charged: Money, luc: Money | None) -> Money:
    """What a candidate is owed when they were charged above the LUC."""
    if luc is None or charged <= luc:
        return Money(0)
    return charged - luc


@dataclass
class ChargeBook:
    """Every charge on the books, which is what the rule is measured against."""

    charges: list[Charge]

    def add(self, charge: Charge) -> None:
        self.charges.append(charge)

    def lowest(
        self,
        station: str,
        daypart: str,
        grade: ClassOfTime,
        length: SpotLength,
        first: date,
        last: date,
    ) -> Money | None:
        return lowest_unit_charge(self.charges, station, daypart, grade, length, first, last)

    def for_race(
        self, race: Race, station: str, daypart: str, grade: ClassOfTime, length: SpotLength
    ) -> Money | None:
        return self.lowest(station, daypart, grade, length, race.window_opens, race.election_day)

    def cheapest_class(
        self, station: str, daypart: str, length: SpotLength, first: date, last: date
    ) -> tuple[ClassOfTime, Money] | None:
        """The class with the lowest charge, for a buyer who did not name one."""
        found = []
        for grade in ClassOfTime:
            lowest = self.lowest(station, daypart, grade, length, first, last)
            if lowest is not None:
                found.append((grade, lowest))
        if not found:
            return None
        return min(found, key=lambda pair: (pair[1], -pair[0].rank))

    def __len__(self) -> int:
        return len(self.charges)
