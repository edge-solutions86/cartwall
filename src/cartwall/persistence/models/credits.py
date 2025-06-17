"""Credit memos, which are how an invoice gets corrected without being edited.

An invoice that has gone out does not change. When it turns out to be wrong,
the answer is a second document that says so and by how much, and both stay on
file. The agency's payables clerk applies the credit against the invoice and
their ledger and ours both still add up.

A credit points at the invoice it corrects and carries a reason from a fixed
list. The list is short and it is not free text, because the month end report
groups by it and "cust complaint", "customer complaint" and "complaint" are
three rows nobody wanted.

Money on a credit is stored positive and applied negative. That is the way the
office reads it on paper, and the arithmetic is done once, here, rather than at
every place that adds a column up.
"""

from __future__ import annotations

from datetime import date
from enum import Enum

from sqlalchemy import Boolean, Date, ForeignKey, Integer, String
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ...money import Money
from ..base import CODE_LENGTH, NAME_LENGTH, Base, Cents, EnumValue
from .billing import InvoiceRow


class Reason(Enum):
    """Why a credit was written. Fixed, because the report groups by it."""

    #: A spot that did not run and was billed anyway.
    DID_NOT_RUN = "did not run"
    #: Ran, but not where it was sold, and the client would not accept it.
    WRONG_DAYPART = "wrong daypart"
    #: The lowest unit charge moved after the invoice went out.
    POLITICAL_REBATE = "political rebate"
    #: Billed at the wrong rate.
    RATE_ERROR = "rate error"
    #: Everything else, which needs a note.
    OTHER = "other"

    @property
    def needs_a_note(self) -> bool:
        return self is Reason.OTHER


class CreditRow(Base):
    __tablename__ = "credit_memo"

    number: Mapped[str] = mapped_column(String(24), primary_key=True)
    #: The invoice this corrects. A credit never floats free of one.
    invoice_number: Mapped[str] = mapped_column(ForeignKey("invoice.number"))
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code"))
    payer: Mapped[str] = mapped_column(String(CODE_LENGTH))
    raised: Mapped[date] = mapped_column(Date, index=True)
    reason: Mapped[Reason] = mapped_column(EnumValue(Reason))
    note: Mapped[str] = mapped_column(String(NAME_LENGTH), default="")

    #: Positive on the document, applied negative. See the note above.
    gross: Mapped[Money] = mapped_column(Cents)
    commission: Mapped[Money] = mapped_column(Cents)
    net: Mapped[Money] = mapped_column(Cents)

    sent: Mapped[bool] = mapped_column(Boolean, default=False)
    spots: Mapped[int] = mapped_column(Integer, default=0)

    invoice: Mapped[InvoiceRow] = relationship()

    def __repr__(self) -> str:
        return f"<CreditRow {self.number} against {self.invoice_number}>"
