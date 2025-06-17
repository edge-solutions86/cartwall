"""Writing a credit, and working out what an account actually owes after them.

Two jobs. Raising one credit, deliberately, with a reason and a number off the
counter. And answering the only question the business office asks about them,
which is what is left owing on an invoice once its credits are applied.

The rules a credit has to hold to:

* It points at an invoice, always. A credit that floats free of one is a credit
  nobody can apply and an agency will not take.
* It cannot exceed what is left on the invoice. Crediting more than was billed
  turns an invoice into a payable, and if that is genuinely what somebody wants
  it is a refund and a different conversation.
* Commission comes off it at the same rate the invoice carried. Crediting gross
  and keeping the commission is a way of quietly charging an agency fifteen
  percent for a spot that did not run.
* ``OTHER`` needs a note. The month end report groups by reason and a row
  called other with nothing beside it is a row somebody has to go and ask about.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select

from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event
from ..money import ZERO, Money, commission, to_net, total
from ..persistence.models.billing import InvoiceRow
from ..persistence.models.credits import CreditRow, Reason
from ..persistence.unitofwork import UnitOfWork
from .numbering import Numbering

#: The series credit numbers are drawn from. Separate from invoices on purpose.
SERIES = "CRD"


class CreditError(ValidationError):
    """A credit that cannot be written as asked."""

    code = "bad_credit"


class OverCredited(ConflictError):
    """More than is left on the invoice."""

    code = "over_credited"


@dataclass(frozen=True, slots=True)
class Standing:
    """What an invoice comes to once its credits are applied."""

    invoice: str
    billed: Money
    credited: Money

    @property
    def owing(self) -> Money:
        return self.billed - self.credited

    @property
    def is_settled(self) -> bool:
        return self.owing == ZERO

    def __str__(self) -> str:
        return f"{self.invoice} billed {self.billed} less {self.credited} leaves {self.owing}"


def credits_against(uow: UnitOfWork, invoice: str) -> list[CreditRow]:
    return list(
        uow.session.scalars(
            select(CreditRow).where(CreditRow.invoice_number == invoice).order_by(CreditRow.number)
        ).all()
    )


def standing(uow: UnitOfWork, invoice_number: str) -> Standing:
    """What is left owing on one invoice."""
    invoice = uow.session.get(InvoiceRow, invoice_number)
    if invoice is None:
        raise LookupFailure(f"invoice {invoice_number} is not on file", invoice=invoice_number)
    applied = total([row.net for row in credits_against(uow, invoice_number)])
    return Standing(invoice_number, invoice.net, applied)


def raise_credit(
    uow: UnitOfWork,
    invoice_number: str,
    gross: Money,
    reason: Reason,
    raised_on: date,
    note: str = "",
    spots: int = 0,
) -> CreditRow:
    """Write one credit against one invoice."""
    if gross.cents <= 0:
        raise CreditError(f"a credit of {gross} is not a credit", amount=str(gross))
    if reason.needs_a_note and not note.strip():
        raise CreditError("a credit for some other reason needs a note")
    invoice = uow.session.get(InvoiceRow, invoice_number)
    if invoice is None:
        raise LookupFailure(f"invoice {invoice_number} is not on file", invoice=invoice_number)

    taken = commission(gross, invoice.commission_rate)
    net = to_net(gross, invoice.commission_rate)
    left = standing(uow, invoice_number).owing
    if net > left:
        raise OverCredited(
            f"{invoice_number} has {left} left and this credit is {net}",
            invoice=invoice_number,
            left=str(left),
            asked=str(net),
        )

    row = CreditRow(
        number=Numbering(uow.session).next(SERIES, raised_on.year),
        invoice_number=invoice_number,
        advertiser=invoice.advertiser,
        payer=invoice.payer,
        raised=raised_on,
        reason=reason,
        note=note.strip(),
        gross=gross,
        commission=taken,
        net=net,
        spots=spots,
    )
    uow.session.add(row)
    uow.session.flush()
    event(
        "credit.raised",
        credit=row.number,
        invoice=invoice_number,
        payer=invoice.payer,
        reason=reason.value,
        gross=str(gross),
        net=str(net),
    )
    return row


def credit_the_rebate(
    uow: UnitOfWork, invoice_number: str, amount: Money, raised_on: date
) -> CreditRow:
    """The political case, which has its own reason and needs no note."""
    return raise_credit(uow, invoice_number, amount, Reason.POLITICAL_REBATE, raised_on)


def by_reason(uow: UnitOfWork, first: date, last: date) -> dict[str, Money]:
    """What was credited in a span, grouped the way the month end reads it."""
    if last < first:
        raise CreditError("the span runs backwards")
    rows = uow.session.scalars(
        select(CreditRow).where(CreditRow.raised >= first, CreditRow.raised <= last)
    ).all()
    tally: dict[str, Money] = {}
    for row in rows:
        tally[row.reason.value] = tally.get(row.reason.value, ZERO) + row.net
    return dict(sorted(tally.items(), key=lambda pair: (-pair[1].cents, pair[0])))


def outstanding(uow: UnitOfWork) -> list[Standing]:
    """Every invoice with something still owing, most owing first."""
    invoices = uow.session.scalars(select(InvoiceRow).order_by(InvoiceRow.number)).all()
    found = [standing(uow, invoice.number) for invoice in invoices]
    open_ones = [entry for entry in found if not entry.is_settled]
    open_ones.sort(key=lambda entry: (-entry.owing.cents, entry.invoice))
    return open_ones
