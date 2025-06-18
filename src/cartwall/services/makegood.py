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

    def accepted(self) -> Makegood:
        return _with(self, Offer.ACCEPTED)

    def refused(self) -> Makegood:
        return _with(self, Offer.REFUSED)

    def expired(self) -> Makegood:
        return _with(self, Offer.EXPIRED)

    def __str__(self) -> str:
        note = " short" if self.short else ""
        return (
            f"{self.debt.order}-{self.debt.line} offered {self.daypart} on "
            f"{self.day} at {self.rate}{note} ({self.state.value})"
        )


def _with(offer: Makegood, state: Offer) -> Makegood:
    if offer.state.is_settled:
        raise MakegoodError(f"that offer was already {offer.state.value}", state=offer.state.value)
    return Makegood(
        debt=offer.debt,
        day=offer.day,
        daypart=offer.daypart,
        rate=offer.rate,
        state=state,
        short=offer.short,
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
class Ledger:
    """What is owed, what has been offered, and what is still open."""

    debts: list[Debt] = field(default_factory=list)
    offers: list[Makegood] = field(default_factory=list)

    def owe(self, debt: Debt) -> None:
        self.debts.append(debt)

    def propose(self, debt: Debt, day: date, daypart: str, rate: Money) -> Makegood:
        if debt not in self.debts:
            raise NotOwed(
                f"nothing is owed on {debt.order}-{debt.line} for {debt.day}",
                order=debt.order,
                line=debt.line,
            )
        if self.open_offer_for(debt) is not None:
            raise ConflictError(
                f"{debt.order}-{debt.line} already has an offer open for {debt.day}",
                order=debt.order,
                line=debt.line,
            )
        made = offer(debt, day, daypart, rate)
        self.offers.append(made)
        return made

    def settle(self, made: Makegood, state: Offer) -> Makegood:
        """Record the client's answer, in place."""
        if made not in self.offers:
            raise MakegoodError("that offer is not on this ledger")
        settled = _with(made, state)
        self.offers[self.offers.index(made)] = settled
        return settled

    def open_offer_for(self, debt: Debt) -> Makegood | None:
        for made in self.offers:
            if made.debt == debt and not made.state.is_settled:
                return made
        return None

    def settled_offer_for(self, debt: Debt) -> Makegood | None:
        for made in self.offers:
            if made.debt == debt and made.state is Offer.ACCEPTED:
                return made
        return None

    def still_owed(self) -> list[Debt]:
        """Debts with no accepted makegood against them, oldest first."""
        found = [debt for debt in self.debts if self.settled_offer_for(debt) is None]
        return sorted(found, key=lambda debt: (debt.day, debt.order, debt.line))

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

    def expire_before(self, day: date) -> list[Makegood]:
        """Withdraw offers whose air date has passed without an answer."""
        withdrawn = []
        for index, made in enumerate(list(self.offers)):
            if not made.state.is_settled and made.day < day:
                self.offers[index] = _with(made, Offer.EXPIRED)
                withdrawn.append(self.offers[index])
        return withdrawn

    def __len__(self) -> int:
        return len(self.debts)
