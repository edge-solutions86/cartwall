"""Owing a client a spot, offering one back, and closing the debt.

A makegood is what a station gives when a spot it was paid for did not run.
Three things can create the debt and they arrive from three different places:

* The as-run says the spot was skipped or errored. Read off the posted log.
* A higher class bumped it. Read off the bump queue.
* The log was never built for a day the flight covered. Read off neither, which
  is why the shortfall from the build run is carried into this too.

Whatever the cause, the debt is the same shape: one line owes one spot. What
differs is what may be offered back, and there is a rule the sales side cares
about more than any other in this module.

**A makegood has to be worth what was lost.** A drive time spot is not made
good with an overnight one, and an offer that is cheaper than what was missed
is an offer a client is entitled to refuse. So an offer is checked against the
rate of what it replaces, and one that is worth less is marked as such rather
than being quietly booked.

Offers are offers. Nothing airs because a makegood was proposed. The client
accepts it, and only then is the spot placed and the debt closed. A makegood
that was offered and never accepted is still owed at the end of the month, and
that is the number the credit run reads.

**Nothing in here writes anything down.** Debts are raised and offers are made
and settled through the repository, which is the only place that can do it
durably and the only place that can tell one of several identical debts from
another. This module holds the rules and the shapes; the ledger assembles what
the stored rows mean.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from enum import Enum

from ..domain.ratecard import ClassOfTime
from ..domain.spot import SpotLength
from ..errors import ConflictError, ValidationError
from ..money import Money


class MakegoodError(ValidationError):
    """An offer that cannot be made as described."""

    code = "bad_makegood"


class NotOwed(ConflictError):
    """Nothing is owed on that line."""

    code = "not_owed"


class Cause(Enum):
    """Why the spot is owed. Three sources, three different reports."""

    DID_NOT_RUN = "did not run"
    BUMPED = "bumped"
    NEVER_SCHEDULED = "never scheduled"


class Offer(Enum):
    OPEN = "open"
    ACCEPTED = "accepted"
    REFUSED = "refused"
    #: Withdrawn by the station, usually because the flight ended first.
    EXPIRED = "expired"

    @property
    def is_settled(self) -> bool:
        return self is not Offer.OPEN


@dataclass(frozen=True, slots=True)
class Debt:
    """One spot owed to one line."""

    order: str
    line: int
    advertiser: str
    station: str
    daypart: str
    grade: ClassOfTime
    length: SpotLength
    rate: Money
    day: date
    cause: Cause

    @property
    def key(self) -> tuple[str, int]:
        return (self.order, self.line)

    def __str__(self) -> str:
        return (
            f"{self.order}-{self.line} {self.station} {self.day} "
            f"{self.cause.value}, worth {self.rate}"
        )


@dataclass(frozen=True, slots=True)
class Makegood:
    """One offer against one debt."""

    debt: Debt
    day: date
    daypart: str
    rate: Money
    state: Offer = Offer.OPEN
    #: Set when the offer is worth less than what it replaces.
    short: bool = False

    def __post_init__(self) -> None:
        if self.day < self.debt.day:
            raise MakegoodError(
                f"a makegood for {self.debt.day} cannot run on {self.day}",
                owed=str(self.debt.day),
                offered=str(self.day),
            )

    @property
    def shortfall(self) -> Money:
        gap = self.debt.rate - self.rate
        return gap if gap.cents > 0 else Money(0)

    def __str__(self) -> str:
        note = " short" if self.short else ""
        return (
            f"{self.debt.order}-{self.debt.line} offered {self.daypart} on "
            f"{self.day} at {self.rate}{note} ({self.state.value})"
        )


def offer(debt: Debt, day: date, daypart: str, rate: Money) -> Makegood:
    """Propose one spot back. Marked short when it is worth less."""
    if not daypart:
        raise MakegoodError("a makegood needs a daypart")
    return Makegood(
        debt=debt,
        day=day,
        daypart=daypart,
        rate=rate,
        short=rate < debt.rate,
    )


@dataclass
class Entry:
    """One debt and every offer made against it, oldest offer first.

    This pairing is the whole reason the ledger is not two lists. Four spots
    owed on the same line, the same day and for the same reason are four debts
    that compare equal, so a ledger that matched offers to debts by value
    closed all four the moment one makegood was accepted. Measured, once, on a
    line that was four spots short in a week.
    """

    debt: Debt
    offers: list[Makegood] = field(default_factory=list)

    @property
    def accepted(self) -> Makegood | None:
        for made in self.offers:
            if made.state is Offer.ACCEPTED:
                return made
        return None

    @property
    def open(self) -> Makegood | None:
        for made in self.offers:
            if not made.state.is_settled:
                return made
        return None

    @property
    def is_owed(self) -> bool:
        """Still owed until a makegood against this spot has been accepted."""
        return self.accepted is None

    def __str__(self) -> str:
        answer = str(self.accepted) if self.accepted else "nothing accepted"
        return f"{self.debt} ({answer})"


@dataclass
class Ledger:
    """What is owed and what has been offered back. A read model.

    Nothing here writes. Debts are raised and offers are made and settled
    through :class:`cartwall.persistence.makegoodstore.MakegoodRepository`,
    which is the only place that can do it durably and the only place that can
    tell one of several identical debts from another. This assembles what those
    rows mean and answers the questions the reports ask.
    """

    entries: list[Entry] = field(default_factory=list)

    @property
    def debts(self) -> list[Debt]:
        return [entry.debt for entry in self.entries]

    @property
    def offers(self) -> list[Makegood]:
        return [made for entry in self.entries for made in entry.offers]

    def owe(self, debt: Debt, offers: list[Makegood] | None = None) -> Entry:
        """Put one debt on the ledger, with whatever was offered against it."""
        entry = Entry(debt=debt, offers=list(offers or []))
        self.entries.append(entry)
        return entry

    def owed(self) -> list[Entry]:
        """Entries with no accepted makegood, oldest first."""
        found = [entry for entry in self.entries if entry.is_owed]
        return sorted(found, key=lambda entry: (entry.debt.day, entry.debt.order, entry.debt.line))

    def still_owed(self) -> list[Debt]:
        return [entry.debt for entry in self.owed()]

    def open_offers(self) -> list[Makegood]:
        """Offers nobody has answered, soonest air date first."""
        found = [entry.open for entry in self.entries if entry.open is not None]
        return sorted(found, key=lambda made: made.day)

    def owed_by_line(self) -> dict[tuple[str, int], int]:
        tally: dict[tuple[str, int], int] = {}
        for debt in self.still_owed():
            tally[debt.key] = tally.get(debt.key, 0) + 1
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    def owed_value(self) -> Money:
        """What the open debt is worth at the rate it was sold at."""
        return Money(sum(debt.rate.cents for debt in self.still_owed()))

    def by_cause(self) -> dict[str, int]:
        tally: dict[str, int] = {}
        for debt in self.still_owed():
            tally[debt.cause.value] = tally.get(debt.cause.value, 0) + 1
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    def __len__(self) -> int:
        return len(self.entries)
