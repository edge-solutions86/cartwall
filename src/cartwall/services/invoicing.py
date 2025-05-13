"""The month end run: every order that aired something gets an invoice.

Run on the Monday after the broadcast month closes, once, for the whole house.
The three rules it lives by:

**Only aired spots.** The run reads the posted as-run, not the log. A day whose
as-run has not been posted has no aired spots on it, contributes nothing, and
that is the correct answer rather than a bug: billing a day nobody has
reconciled is how a station bills for spots that did not run.

**One invoice per order per month, once.** Running the job twice does not raise
a second invoice, and it does not quietly rewrite the first one either. An
order that has already been invoiced for August is skipped and reported as
skipped. A corrected invoice is a credit and a re-raise, done deliberately.

**The number comes off the counter.** Gapless, in the same transaction, so a
run that fails halfway leaves no hole and no orphan.

An order that aired nothing gets no invoice at all rather than a zero one.
Agencies reconcile against what they are sent, and a nil invoice is a piece of
paper somebody has to decide what to do with.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..airtime import AirTime
from ..billing.invoice import Detail, Invoice, summarise
from ..broadcast import BroadcastMonth
from ..errors import ConflictError, ValidationError
from ..logs.approval import LogState
from ..persistence.models.billing import (
    InvoiceDetailRow,
    InvoiceRow,
    InvoiceSummaryRow,
)
from ..persistence.models.logs import LogRow, SpotRow
from ..persistence.unitofwork import UnitOfWork
from ..reconcile.matcher import ON_TIME
from .numbering import Numbering

#: The series invoice numbers are drawn from.
SERIES = "INV"


class InvoicingError(ValidationError):
    """A run that could not be made."""

    code = "bad_invoicing"


class AlreadyInvoiced(ConflictError):
    """This order has an invoice for this month already."""

    code = "already_invoiced"


@dataclass
class RunReport:
    """What one month end run did."""

    month: BroadcastMonth
    raised: list[str] = field(default_factory=list)
    skipped: list[tuple[str, str]] = field(default_factory=list)
    nothing_aired: list[str] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.raised)

    def __str__(self) -> str:
        return (
            f"{self.month}: {self.count} raised, {len(self.skipped)} already done, "
            f"{len(self.nothing_aired)} with nothing to bill"
        )


def aired_in(uow: UnitOfWork, order: str, month: BroadcastMonth) -> list[SpotRow]:
    """Every spot of one order that went out in one broadcast month."""
    return list(
        uow.session.scalars(
            select(SpotRow)
            .join(LogRow)
            .where(
                SpotRow.order_number == order,
                SpotRow.aired.is_(True),
                LogRow.state == LogState.AIRED,
                LogRow.day >= month.start,
                LogRow.day <= month.end,
            )
            .order_by(LogRow.day, SpotRow.at)
        ).all()
    )


def orders_with_air(uow: UnitOfWork, month: BroadcastMonth) -> list[str]:
    """Order numbers with at least one aired spot in the month, in order."""
    numbers = uow.session.scalars(
        select(SpotRow.order_number)
        .join(LogRow)
        .where(
            SpotRow.aired.is_(True),
            LogRow.state == LogState.AIRED,
            LogRow.day >= month.start,
            LogRow.day <= month.end,
        )
        .distinct()
    ).all()
    return sorted(numbers)


def existing(uow: UnitOfWork, order: str, month: BroadcastMonth) -> InvoiceRow | None:
    return uow.session.scalars(
        select(InvoiceRow).where(
            InvoiceRow.order_number == order,
            InvoiceRow.year == month.year,
            InvoiceRow.month == month.month,
        )
    ).first()


def build_invoice(
    uow: UnitOfWork, order_number: str, month: BroadcastMonth, raised_on: date
) -> Invoice:
    """The invoice for one order and one month, unnumbered and unwritten."""
    order = uow.orders.get(order_number)
    accounts = uow.accounts.book()
    rates = {line.number: line.rate for line in order.lines}
    places = {line.number: (line.station, line.daypart) for line in order.lines}

    details = []
    for spot in aired_in(uow, order_number, month):
        rate = rates.get(spot.line)
        if rate is None:
            raise InvoicingError(
                f"{order_number} aired a line {spot.line} that is not on the order",
                order=order_number,
                line=spot.line,
            )
        at = spot.aired_at or spot.at
        details.append(
            Detail(
                day=spot.log.day,
                at=at.twelve_hour(),
                line=spot.line,
                length=spot.length,
                rate=rate,
                cart=spot.cart,
                moved=drifted(at, spot.at),
            )
        )

    invoice = Invoice(
        number="unnumbered",
        order=order_number,
        advertiser=order.advertiser,
        payer=accounts.liable_party(order.advertiser),
        month=month,
        commission_rate=Decimal(accounts.commission_rate(order.advertiser)),
        details=details,
        raised=raised_on,
    )
    invoice.summaries = summarise(details, places)
    invoice.check_adds_up()
    return invoice


def drifted(aired: AirTime, logged: AirTime) -> bool:
    """Whether a spot ran far enough from its logged time to say so on paper."""
    earlier, later = sorted((aired, logged))
    return (later - earlier) > ON_TIME


def store(uow: UnitOfWork, invoice: Invoice) -> InvoiceRow:
    """Write an invoice down, exactly as it stands."""
    row = InvoiceRow(
        number=invoice.number,
        order_number=invoice.order,
        advertiser=invoice.advertiser,
        payer=invoice.payer,
        year=invoice.month.year,
        month=invoice.month.month,
        raised=invoice.raised or date.today(),
        spots=invoice.spots,
        gross=invoice.gross,
        commission_rate=invoice.commission_rate,
        commission=invoice.commission,
        net=invoice.net,
    )
    row.details = [
        InvoiceDetailRow(
            day=detail.day,
            at=_air_time(detail.at),
            line=detail.line,
            length=detail.length,
            rate=detail.rate,
            cart=detail.cart,
            moved=detail.moved,
        )
        for detail in invoice.details
    ]
    row.summaries = [
        InvoiceSummaryRow(
            line=summary.line,
            station=summary.station,
            daypart=summary.daypart,
            length=summary.length,
            rate=summary.rate,
            spots=summary.spots,
            gross=summary.gross,
        )
        for summary in invoice.summaries
    ]
    uow.session.add(row)
    uow.session.flush()
    return row


def _air_time(text: str) -> AirTime:
    return AirTime.parse(text)


def raise_one(uow: UnitOfWork, order: str, month: BroadcastMonth, raised_on: date) -> InvoiceRow:
    """Number and store the invoice for one order. Refuses a second one."""
    if existing(uow, order, month) is not None:
        raise AlreadyInvoiced(
            f"{order} already has an invoice for {month}",
            order=order,
            month=str(month),
        )
    invoice = build_invoice(uow, order, month, raised_on)
    if invoice.is_empty:
        raise InvoicingError(f"{order} aired nothing in {month}", order=order, month=str(month))
    invoice.number = Numbering(uow.session).next(SERIES, raised_on.year)
    return store(uow, invoice)


def run_month(uow: UnitOfWork, month: BroadcastMonth, raised_on: date) -> RunReport:
    """Raise every invoice the month has earned."""
    report = RunReport(month)
    for order in orders_with_air(uow, month):
        if existing(uow, order, month) is not None:
            report.skipped.append((order, "already invoiced"))
            continue
        invoice = build_invoice(uow, order, month, raised_on)
        if invoice.is_empty:
            report.nothing_aired.append(order)
            continue
        invoice.number = Numbering(uow.session).next(SERIES, raised_on.year)
        store(uow, invoice)
        report.raised.append(invoice.number)
    return report
