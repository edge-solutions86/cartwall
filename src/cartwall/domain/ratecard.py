"""Rate cards, which price a thirty and derive everything else from it.

A card quotes one number per daypart per class of time, and that number is for
a thirty. Every other length comes off it by a factor, and the factors are not
proportional: a sixty is one and three quarters of a thirty, not two, because
the second thirty of a sixty does not cost the station another unit of
listener attention, it costs another unit of inventory. A fifteen is sixty
five percent, which is more than half, for the same reason in reverse.

Class of time is the other axis and it is the one that gets forgotten. The
same thirty in the same daypart is three different prices depending on what
the buyer is promised:

* **Fixed** runs where it was sold and cannot be moved. Most expensive.
* **Preemptible with notice** can be bumped if the station gives two weeks.
* **Immediately preemptible** can be bumped the morning of. Cheapest, and the
  reason a station can sell out and still take a political order.

Cards are effective dated and never edited. A rate rise is a new card, because
an order written last October is still entitled to last October's card and the
invoice run has to be able to prove which one it used.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import Money
from .spot import SpotLength

#: Multiplied against the quoted thirty. Not proportional, on purpose.
LENGTH_FACTORS: dict[int, Decimal] = {
    10: Decimal("0.50"),
    15: Decimal("0.65"),
    20: Decimal("0.75"),
    30: Decimal("1.00"),
    45: Decimal("1.40"),
    60: Decimal("1.75"),
    90: Decimal("2.50"),
    120: Decimal("3.25"),
}


class RateError(ValidationError):
    """A rate that cannot be quoted."""

    code = "bad_rate"


class ClassOfTime(Enum):
    FIXED = "fixed"
    NOTICE = "preemptible with notice"
    IMMEDIATE = "immediately preemptible"

    @property
    def may_be_preempted(self) -> bool:
        return self is not ClassOfTime.FIXED

    @property
    def notice_days(self) -> int:
        """Days of warning owed before this class can be bumped."""
        if self is ClassOfTime.NOTICE:
            return 14
        return 0

    @property
    def rank(self) -> int:
        """Higher outbids lower when two orders want the same unit."""
        return {ClassOfTime.IMMEDIATE: 1, ClassOfTime.NOTICE: 2, ClassOfTime.FIXED: 3}[self]


@dataclass(frozen=True, slots=True)
class Quote:
    """One quoted thirty, for one daypart in one class."""

    daypart: str
    grade: ClassOfTime
    thirty: Money

    def __post_init__(self) -> None:
        if not self.daypart:
            raise RateError("a quote needs a daypart")
        if self.thirty.cents < 0:
            raise RateError(f"{self.daypart} is quoted at {self.thirty}")

    def at_length(self, length: SpotLength) -> Money:
        try:
            factor = LENGTH_FACTORS[length.seconds]
        except KeyError:
            raise RateError(f"no length factor is filed for {length}") from None
        return self.thirty.scale(factor)

    def __str__(self) -> str:
        return f"{self.daypart} {self.grade.value} {self.thirty}"


@dataclass(frozen=True, slots=True)
class RateCard:
    """One station's card, in force from a date until the next one replaces it."""

    code: str
    station: str
    effective: date
    quotes: tuple[Quote, ...]

    def __post_init__(self) -> None:
        if not self.code:
            raise RateError("a card needs a code")
        seen = set()
        for quote in self.quotes:
            key = (quote.daypart, quote.grade)
            if key in seen:
                raise RateError(
                    f"card {self.code} quotes {quote.daypart} {quote.grade.value} twice"
                )
            seen.add(key)

    @classmethod
    def build(cls, code: str, station: str, effective: date, rows: list[tuple]) -> RateCard:
        """Rows of ``(daypart, class, dollars)``, which is how a card is typed in."""
        quotes = tuple(
            Quote(daypart, grade, Money.dollars(Decimal(str(dollars))))
            for daypart, grade, dollars in rows
        )
        return cls(code, station, effective, quotes)

    def quote(self, daypart: str, grade: ClassOfTime) -> Quote:
        for candidate in self.quotes:
            if candidate.daypart == daypart and candidate.grade is grade:
                return candidate
        raise RateError(f"card {self.code} does not quote {daypart} at {grade.value}")

    def rate(self, daypart: str, grade: ClassOfTime, length: SpotLength) -> Money:
        return self.quote(daypart, grade).at_length(length)

    def dayparts(self) -> tuple[str, ...]:
        """In the order they were filed, without repeats."""
        found: list[str] = []
        for quote in self.quotes:
            if quote.daypart not in found:
                found.append(quote.daypart)
        return tuple(found)

    def covers(self, daypart: str) -> bool:
        return any(quote.daypart == daypart for quote in self.quotes)

    def __len__(self) -> int:
        return len(self.quotes)


@dataclass
class RateCardBook:
    """Every card ever filed, per station, so an old order can be repriced."""

    cards: list[RateCard] = field(default_factory=list)

    def file(self, card: RateCard) -> None:
        clash = [
            filed
            for filed in self.cards
            if filed.station == card.station and filed.effective == card.effective
        ]
        if clash:
            raise RateError(
                f"{card.station} already has card {clash[0].code} effective {card.effective}"
            )
        self.cards.append(card)
        self.cards.sort(key=lambda filed: (filed.station, filed.effective))

    def in_force(self, station: str, on: date) -> RateCard:
        """The newest card for ``station`` that had taken effect by ``on``."""
        candidates = [
            card for card in self.cards if card.station == station and card.effective <= on
        ]
        if not candidates:
            raise RateError(f"{station} has no card in force on {on}")
        return candidates[-1]

    def rate(
        self,
        station: str,
        on: date,
        daypart: str,
        grade: ClassOfTime,
        length: SpotLength,
    ) -> Money:
        return self.in_force(station, on).rate(daypart, grade, length)

    def history(self, station: str) -> list[RateCard]:
        return [card for card in self.cards if card.station == station]
