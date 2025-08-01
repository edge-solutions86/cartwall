"""The nightly run that turns what is owed into debts somebody can answer.

:mod:`cartwall.services.owing` finds debts. It builds them in memory, hands
back a ledger, and forgets. That was enough while the makegood ledger lived
only for the length of a report, and it stopped being enough the moment offers
had to be made against those debts days later and by somebody else.

This is the join. Gather, then write, then say what changed.

Three things make this safe to run more than once a night, which matters
because it is going to be.

**Raising an existing debt is a no-op.** The store keys a debt on line, day and
cause, so a second run over the same span finds what is already there. The
count that comes back is new debts, not debts seen.

**Nothing is ever un-raised.** A debt that the gather no longer finds stays on
file. It could vanish for an honest reason, usually a log being revised, but it
could also vanish because somebody re-posted the wrong as-run, and a run that
quietly cancels what a client is owed is the one failure here that nobody would
notice until the client rang.

**Offers whose air date has gone by are expired first.** An open offer for last
Tuesday is not an open offer, it is a spot the station has already failed to
run twice, and leaving it open makes the debt look answered on every report.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from ..logging import event, warn
from ..money import Money
from ..persistence.unitofwork import UnitOfWork
from .makegood import Cause
from .owing import gather


@dataclass
class Raised:
    """What one run of the nightly job changed."""

    first: date
    last: date
    #: Debts the gather found, whether or not they were already on file.
    found: int = 0
    #: Of those, the ones that were not on file before.
    new: int = 0
    #: Offers withdrawn because their air date had gone by.
    expired: int = 0
    by_cause: dict[str, int] = field(default_factory=dict)

    @property
    def changed(self) -> bool:
        return bool(self.new or self.expired)

    def __str__(self) -> str:
        return (
            f"{self.first} to {self.last}: {self.new} new of {self.found} owed, "
            f"{self.expired} offer(s) expired"
        )


def raise_owing(
    uow: UnitOfWork,
    first: date,
    last: date,
    scheduled: bool = True,
    today: date | None = None,
) -> Raised:
    """Gather every debt in the span and put the new ones on file.

    ``today`` is the day offers are expired against, and it defaults to the end
    of the span rather than to the wall clock. Running last month's span in
    November should expire last month's offers, not everything.
    """
    ledger = gather(uow, first, last, scheduled=scheduled)
    debts = ledger.debts
    report = Raised(first=first, last=last, found=len(debts))
    report.new = uow.makegoods.raise_all(debts)
    report.expired = len(uow.makegoods.expire_before(today or last))

    tally: dict[str, int] = {}
    for debt in debts:
        tally[debt.cause.value] = tally.get(debt.cause.value, 0) + 1
    report.by_cause = dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))

    event(
        "makegood.raised",
        first=first,
        last=last,
        found=report.found,
        new=report.new,
        expired=report.expired,
    )
    if report.new:
        warn("makegood.owed", first=first, last=last, spots=report.new)
    return report


def owed_value(uow: UnitOfWork, order: str | None = None) -> Money:
    """What is still owed, at the rate each spot was sold at."""
    return uow.makegoods.ledger(order=order).owed_value()


def worst_lines(uow: UnitOfWork, limit: int = 10) -> list[tuple[str, int, int]]:
    """The lines with the most spots outstanding, worst first.

    This is the call the traffic director makes on a Monday morning, and the
    answer is a short list rather than a report, because the useful question is
    which three clients to ring rather than what the total is.
    """
    owed = uow.makegoods.ledger().owed_by_line()
    return [(order, line, count) for (order, line), count in list(owed.items())[:limit]]


def causes(uow: UnitOfWork) -> dict[str, int]:
    """Why the open debt is open, worst first.

    A month where ``never scheduled`` dominates is a month the log builder ran
    out of room, which is a selling problem. One where ``did not run``
    dominates is an automation problem. They get fixed by different people.
    """
    tally = uow.makegoods.ledger().by_cause()
    for cause in Cause:
        tally.setdefault(cause.value, 0)
    return dict(sorted(tally.items(), key=lambda pair: (-pair[1], pair[0])))
