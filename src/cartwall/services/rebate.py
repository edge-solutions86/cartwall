"""Recomputing the lowest unit charge and working out who is owed money.

Run whenever a rate moves inside a political window, and once at the end of the
window whatever happened. The job exists because the lowest unit charge is not
a number the station chooses. It is a measurement of every invoice on the
books, and a discount given to anybody lowers it for everybody entitled to it,
retroactively, including invoices that have already gone out.

The measurement is taken off **invoice detail**, not off orders and not off the
rate card. An order can be written at a rate nobody was ever charged, because
it was cancelled, or the spots did not run, or it was rewritten. What was
charged is what appeared on an invoice, and that is the only thing the rule
looks at.

The comparison is per class and per length, station by station. A candidate who
bought immediately preemptible thirties in morning drive is compared against
every immediately preemptible thirty in morning drive on that station in the
window, and against nothing else. Comparing across classes is the mistake that
makes a station either overpay or get a letter.

Nothing here writes a credit. It works out what is owed and hands back a list.
Issuing the credit is a separate act with a separate document, because a rebate
that appears without a memo is a rebate nobody can explain to an auditor.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from sqlalchemy import select

from ..domain.political import Charge, ChargeBook, Race, owed_back
from ..domain.ratecard import ClassOfTime
from ..errors import ValidationError
from ..logging import event, warn
from ..money import ZERO, Money, total
from ..persistence.models.billing import InvoiceDetailRow, InvoiceRow
from ..persistence.unitofwork import UnitOfWork


class RebateError(ValidationError):
    """A rebate run that cannot be made."""

    code = "bad_rebate"


@dataclass(frozen=True, slots=True)
class Owed:
    """One invoice, one line, and the difference somebody is owed."""

    invoice: str
    order: str
    line: int
    advertiser: str
    station: str
    daypart: str
    grade: ClassOfTime
    charged: Money
    lowest: Money
    spots: int

    @property
    def each(self) -> Money:
        return owed_back(self.charged, self.lowest)

    @property
    def amount(self) -> Money:
        return self.each * self.spots

    def __str__(self) -> str:
        return (
            f"{self.invoice} line {self.line} {self.station} {self.daypart}: "
            f"{self.spots} at {self.charged} against {self.lowest}, owed {self.amount}"
        )


@dataclass
class RebateReport:
    """What one recomputation found."""

    race: Race
    owed: list[Owed] = field(default_factory=list)
    checked: int = 0

    @property
    def total(self) -> Money:
        return total([entry.amount for entry in self.owed])

    @property
    def is_clean(self) -> bool:
        return not self.owed

    def for_advertiser(self, code: str) -> list[Owed]:
        return [entry for entry in self.owed if entry.advertiser == code]

    def by_advertiser(self) -> dict[str, Money]:
        """What each account is owed, worst first."""
        tally: dict[str, Money] = {}
        for entry in self.owed:
            tally[entry.advertiser] = tally.get(entry.advertiser, ZERO) + entry.amount
        return dict(sorted(tally.items(), key=lambda pair: (-pair[1].cents, pair[0])))

    def __str__(self) -> str:
        return (
            f"{self.race}: {self.checked} line(s) checked, "
            f"{len(self.owed)} owed, {self.total} in total"
        )


def charge_book(uow: UnitOfWork, first: date, last: date) -> ChargeBook:
    """Every rate that appeared on an invoice for air in the window.

    One charge per invoice summary line rather than one per spot. The rule is
    about the rate charged for a unit of time, and every spot on a summary line
    was charged the same rate by construction.
    """
    rows = uow.session.scalars(
        select(InvoiceDetailRow)
        .join(InvoiceRow)
        .where(InvoiceDetailRow.day >= first, InvoiceDetailRow.day <= last)
        .order_by(InvoiceDetailRow.day, InvoiceDetailRow.id)
    ).all()
    orders = {}
    charges = []
    for row in rows:
        invoice = row.invoice
        if invoice.order_number not in orders:
            orders[invoice.order_number] = uow.orders.get(invoice.order_number)
        order = orders[invoice.order_number]
        try:
            line = order.line(row.line)
        except Exception:
            continue
        charges.append(
            Charge(
                station=line.station,
                daypart=line.daypart,
                grade=line.grade,
                length=row.length,
                rate=row.rate,
                day=row.day,
                advertiser=invoice.advertiser,
            )
        )
    return ChargeBook(charges)


def owed_for(
    uow: UnitOfWork, race: Race, advertisers: list[str], book: ChargeBook | None = None
) -> RebateReport:
    """What each named political account is owed for air inside the window."""
    if not advertisers:
        raise RebateError("a rebate run needs at least one political account")
    first, last = race.window_opens, race.election_day
    book = book if book is not None else charge_book(uow, first, last)
    report = RebateReport(race)

    invoices = uow.session.scalars(
        select(InvoiceRow).where(InvoiceRow.advertiser.in_(advertisers)).order_by(InvoiceRow.number)
    ).all()
    for invoice in invoices:
        order = uow.orders.get(invoice.order_number)
        for summary in invoice.summaries:
            try:
                line = order.line(summary.line)
            except Exception:
                warn(
                    "rebate.orphan",
                    invoice=invoice.number,
                    line=summary.line,
                    order=invoice.order_number,
                )
                continue
            spots = _spots_in_window(invoice, summary.line, first, last)
            if not spots:
                continue
            report.checked += 1
            lowest = book.lowest(
                line.station, line.daypart, line.grade, summary.length, first, last
            )
            if lowest is None or summary.rate <= lowest:
                continue
            report.owed.append(
                Owed(
                    invoice=invoice.number,
                    order=invoice.order_number,
                    line=summary.line,
                    advertiser=invoice.advertiser,
                    station=line.station,
                    daypart=line.daypart,
                    grade=line.grade,
                    charged=summary.rate,
                    lowest=lowest,
                    spots=spots,
                )
            )
    event(
        "rebate.checked",
        race=str(race),
        lines=report.checked,
        owed=len(report.owed),
        amount=str(report.total),
    )
    return report


def _spots_in_window(invoice: InvoiceRow, line: int, first: date, last: date) -> int:
    return sum(
        1 for detail in invoice.details if detail.line == line and first <= detail.day <= last
    )
