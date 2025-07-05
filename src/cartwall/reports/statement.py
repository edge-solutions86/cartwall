"""The statement, and the aging that is the only part anybody reads.

A statement lists everything a payer owes and how old each piece is. The list
is courtesy. The aging is the document: an agency at ninety days is a
conversation with the sales manager, and one at a hundred and twenty is a
conversation about whether they keep placing.

The buckets are the standard four and the boundaries are inclusive at the low
end, which matters more than it sounds. An invoice raised exactly thirty days
ago is in the thirty day bucket, not in current, and a station that rounds that
the other way reports a month that looks a month better than it is.

Age is measured from the day the invoice was raised, not from the last day of
the month it covers and not from when it was sent. Raised is the only one of
the three that is a fact on file, and the other two drift.

Credits age with the invoice they correct rather than with the day they were
written. A credit written today against a ninety day invoice does not make that
money current; it makes it less money at ninety days.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from ..errors import ValidationError
from ..money import ZERO, Money


class StatementError(ValidationError):
    """A statement that cannot be drawn."""

    code = "bad_statement"


class Bucket(Enum):
    """The four ages, and the day count each one opens at."""

    CURRENT = 0
    THIRTY = 30
    SIXTY = 60
    NINETY = 90

    @property
    def label(self) -> str:
        return {
            Bucket.CURRENT: "current",
            Bucket.THIRTY: "30 days",
            Bucket.SIXTY: "60 days",
            Bucket.NINETY: "90 plus",
        }[self]

    @classmethod
    def of(cls, days: int) -> Bucket:
        """Which bucket an age falls in. Inclusive at the low end."""
        if days >= Bucket.NINETY.value:
            return Bucket.NINETY
        if days >= Bucket.SIXTY.value:
            return Bucket.SIXTY
        if days >= Bucket.THIRTY.value:
            return Bucket.THIRTY
        return Bucket.CURRENT


@dataclass(frozen=True, slots=True)
class Item:
    """One invoice on a statement, with what is left on it."""

    invoice: str
    order: str
    raised: date
    billed: Money
    credited: Money

    @property
    def owing(self) -> Money:
        return self.billed - self.credited

    def age(self, on: date) -> int:
        if on < self.raised:
            raise StatementError(
                f"invoice {self.invoice} was raised on {self.raised}, after {on}",
                invoice=self.invoice,
            )
        return (on - self.raised).days

    def bucket(self, on: date) -> Bucket:
        return Bucket.of(self.age(on))

    def __str__(self) -> str:
        return f"{self.invoice:<12} {self.raised:%m/%d/%y}  {self.owing!s:>12}"


@dataclass
class Statement:
    """One payer, everything open, as of one date."""

    payer: str
    on: date
    items: list[Item] = field(default_factory=list)

    @property
    def total(self) -> Money:
        return Money(sum(item.owing.cents for item in self.items))

    @property
    def is_clear(self) -> bool:
        return self.total == ZERO

    def open_items(self) -> list[Item]:
        """Anything still owing, oldest first."""
        found = [item for item in self.items if item.owing != ZERO]
        return sorted(found, key=lambda item: (item.raised, item.invoice))

    def aging(self) -> dict[Bucket, Money]:
        """The four buckets, always all four, even the empty ones."""
        tally = dict.fromkeys(Bucket, ZERO)
        for item in self.open_items():
            tally[item.bucket(self.on)] += item.owing
        return tally

    def past_due(self, days: int = 30) -> Money:
        """Everything older than ``days``. The number that starts the call."""
        return Money(
            sum(item.owing.cents for item in self.open_items() if item.age(self.on) >= days)
        )

    def oldest(self) -> Item | None:
        found = self.open_items()
        return found[0] if found else None

    def __str__(self) -> str:
        return f"{self.payer} as of {self.on:%m/%d/%Y}: {self.total} on {len(self.open_items())}"


def render(statement: Statement) -> str:
    """The statement as it is posted, aging at the foot."""
    lines = [
        f"STATEMENT   {statement.payer}",
        f"AS OF       {statement.on:%m/%d/%Y}",
        "",
    ]
    for item in statement.open_items():
        lines.append(f"  {item}")
    if not statement.open_items():
        lines.append("  nothing outstanding")
    lines.extend(["", f"TOTAL       {statement.total}", ""])
    aging = statement.aging()
    for bucket in Bucket:
        lines.append(f"  {bucket.label:<10} {aging[bucket]}")
    return "\n".join(lines) + "\n"


def worst_first(statements: list[Statement], days: int = 60) -> list[Statement]:
    """Payers with the most past due, for the sales manager's Monday list."""
    with_debt = [entry for entry in statements if entry.past_due(days) != ZERO]
    with_debt.sort(key=lambda entry: (-entry.past_due(days).cents, entry.payer))
    return with_debt
