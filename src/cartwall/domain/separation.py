"""What may not sit next to what, and how far apart it has to be.

Separation is the only part of traffic that is genuinely a constraint problem.
Everything else is arithmetic with an opinion. Here the question is whether a
spot may go where the schedule wants it, and the answer depends on what is
already there.

Four subjects, and they are checked against different things:

* **Advertiser.** The same client twice inside half an hour sounds like a
  mistake even when it was bought that way.
* **Category.** Two car dealers in the same break is the one that gets a
  station fired from an agency list. Categories nest, so the rule has to ask
  the conflict codes rather than compare strings.
* **Cart.** The identical piece of audio twice in an hour. A client with one
  cut on a heavy schedule will hit this and the answer is more copy, not a
  looser rule.
* **Agency.** Rare, and always a specific demand written into one order rather
  than a house rule.

Scope is the other axis, and the two combine rather than nest. A rule scoped to
the break is not "zero minutes", because two spots in the same break can be
four minutes apart while two spots four minutes apart can be in different
breaks. The break rule and the minute rule catch different things and a station
that means both has to file both.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from ..airtime import AirTime, Duration
from ..errors import ValidationError
from .account import Advertiser


class SeparationError(ValidationError):
    """A separation rule that cannot be filed."""

    code = "bad_separation"


class Subject(Enum):
    ADVERTISER = "advertiser"
    CATEGORY = "category"
    CART = "cart"
    AGENCY = "agency"


class Scope(Enum):
    BREAK = "break"
    MINUTES = "minutes"
    HOUR = "hour"


@dataclass(frozen=True, slots=True)
class Candidate:
    """Everything a separation rule needs to know about one placed spot."""

    day: date
    at: AirTime
    break_number: int
    advertiser: Advertiser
    cart: str = ""
    agency: str | None = None
    #: Set on a spot the traffic director pinned. It is checked, never moved.
    fixed: bool = False

    @property
    def hour(self) -> int:
        return self.at.hour

    def gap_to(self, other: Candidate) -> Duration:
        """How far apart two spots on the same day are. Never negative."""
        if self.day != other.day:
            raise SeparationError("two spots on different days have no gap")
        earlier, later = sorted((self.at, other.at))
        return later - earlier

    def __str__(self) -> str:
        return f"{self.advertiser.code} at {self.at.twelve_hour()}"


@dataclass(frozen=True, slots=True)
class SeparationRule:
    """One house rule, filed once and checked against every candidate."""

    code: str
    subject: Subject
    scope: Scope
    minutes: int = 0

    def __post_init__(self) -> None:
        if not self.code:
            raise SeparationError("a separation rule needs a code")
        if self.scope is Scope.MINUTES and self.minutes < 1:
            raise SeparationError(f"{self.code} is scoped to minutes but names none")
        if self.scope is not Scope.MINUTES and self.minutes:
            raise SeparationError(f"{self.code} is scoped to the {self.scope.value}, not minutes")

    @property
    def gap(self) -> Duration:
        return Duration.of_seconds(self.minutes * 60)

    def subjects_touch(self, one: Candidate, other: Candidate) -> bool:
        """Whether this rule has anything to say about these two spots."""
        if self.subject is Subject.ADVERTISER:
            return one.advertiser.code == other.advertiser.code
        if self.subject is Subject.CATEGORY:
            return one.advertiser.conflicts_with(other.advertiser)
        if self.subject is Subject.CART:
            return bool(one.cart) and one.cart == other.cart
        return one.agency is not None and one.agency == other.agency

    def too_close(self, one: Candidate, other: Candidate) -> bool:
        """Whether these two spots break this rule."""
        if one.day != other.day or not self.subjects_touch(one, other):
            return False
        if self.scope is Scope.BREAK:
            return one.break_number == other.break_number and one.hour == other.hour
        if self.scope is Scope.HOUR:
            return one.hour == other.hour
        return one.gap_to(other) < self.gap

    def __str__(self) -> str:
        if self.scope is Scope.MINUTES:
            return f"{self.code}: {self.subject.value} {self.minutes} minutes apart"
        return f"{self.code}: {self.subject.value} not twice in a {self.scope.value}"


@dataclass(frozen=True, slots=True)
class Violation:
    """One rule, broken by one pair of spots."""

    rule: SeparationRule
    against: Candidate
    gap: Duration | None = None

    def __str__(self) -> str:
        if self.gap is None:
            return f"{self.rule.code} against {self.against}"
        return f"{self.rule.code} against {self.against}, {self.gap} apart"


@dataclass
class SeparationBook:
    """The rules one station runs, checked in the order they were filed."""

    rules: list[SeparationRule] = field(default_factory=list)

    def file(self, rule: SeparationRule) -> None:
        if any(filed.code == rule.code for filed in self.rules):
            raise SeparationError(f"rule {rule.code} is already filed")
        self.rules.append(rule)

    def rule(self, code: str) -> SeparationRule:
        for filed in self.rules:
            if filed.code == code:
                return filed
        raise SeparationError(f"rule {code} is not filed")

    def violations(self, candidate: Candidate, neighbours: list[Candidate]) -> list[Violation]:
        """Every rule ``candidate`` breaks against what is already scheduled."""
        found: list[Violation] = []
        for rule in self.rules:
            for other in neighbours:
                if other is candidate or not rule.too_close(candidate, other):
                    continue
                gap = candidate.gap_to(other) if rule.scope is Scope.MINUTES else None
                found.append(Violation(rule, other, gap))
        return found

    def allows(self, candidate: Candidate, neighbours: list[Candidate]) -> bool:
        return not self.violations(candidate, neighbours)

    def __len__(self) -> int:
        return len(self.rules)


def house_rules() -> SeparationBook:
    """What every station in the cluster runs unless somebody files otherwise."""
    book = SeparationBook()
    book.file(SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30))
    book.file(SeparationRule("SEP-CAT-BRK", Subject.CATEGORY, Scope.BREAK))
    book.file(SeparationRule("SEP-CAT-MIN", Subject.CATEGORY, Scope.MINUTES, 15))
    book.file(SeparationRule("SEP-CART", Subject.CART, Scope.HOUR))
    return book
