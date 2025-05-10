"""Invoices, their detail and their summary, stored as they were sent.

An invoice is the one document in this system that is genuinely immutable. The
log gets revised, the order gets rewritten, the as-run gets corrected. An
invoice that has gone to an agency is a statement of what we said we were owed
on a date, and if it turns out to be wrong the answer is a credit memo and not
an edit.

So the money is stored, not derived. The gross on the row is the gross that was
printed. Recomputing it from the detail on read would be a way of quietly
changing an invoice when a rate card is corrected six weeks later, and the
agency's copy would not change with it.

The detail is stored too, denormalised, rather than being read back off the
spots. The spots can be revised. The invoice cannot.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    Date,
    ForeignKey,
    Integer,
    Numeric,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ...airtime import AirTime
from ...domain.spot import SpotLength
from ...money import Money
from ..base import CODE_LENGTH, Base, Cents, ClockTenths, Seconds
from .accounts import AdvertiserRow


class InvoiceRow(Base):
    __tablename__ = "invoice"
    __table_args__ = (
        UniqueConstraint(
            "order_number", "year", "month", name="uq_invoice_order_number_year_month"
        ),
    )

    number: Mapped[str] = mapped_column(String(24), primary_key=True)
    order_number: Mapped[str] = mapped_column(ForeignKey("sales_order.number"))
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code"))
    #: Who the invoice goes to. The agency when there is one.
    payer: Mapped[str] = mapped_column(String(CODE_LENGTH))
    #: The broadcast month, which is not the calendar month.
    year: Mapped[int] = mapped_column(Integer)
    month: Mapped[int] = mapped_column(Integer)
    raised: Mapped[date] = mapped_column(Date, index=True)

    spots: Mapped[int] = mapped_column(Integer, default=0)
    #: Printed, not derived. See the note at the top of this module.
    gross: Mapped[Money] = mapped_column(Cents)
    commission_rate: Mapped[Decimal] = mapped_column(Numeric(5, 4), default=Decimal("0"))
    commission: Mapped[Money] = mapped_column(Cents)
    net: Mapped[Money] = mapped_column(Cents)

    sent: Mapped[bool] = mapped_column(Boolean, default=False)

    details: Mapped[list[InvoiceDetailRow]] = relationship(
        back_populates="invoice",
        cascade="all, delete-orphan",
        order_by="(InvoiceDetailRow.day, InvoiceDetailRow.at)",
    )
    summaries: Mapped[list[InvoiceSummaryRow]] = relationship(
        back_populates="invoice",
        cascade="all, delete-orphan",
        order_by="InvoiceSummaryRow.line",
    )
    account: Mapped[AdvertiserRow] = relationship()

    def __repr__(self) -> str:
        return f"<InvoiceRow {self.number} {self.year:04d}-{self.month:02d}>"


class InvoiceDetailRow(Base):
    __tablename__ = "invoice_detail"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    invoice_number: Mapped[str] = mapped_column(
        ForeignKey("invoice.number", ondelete="CASCADE")
    )
    day: Mapped[date] = mapped_column(Date)
    #: When it really ran, off the as-run. Not when it was logged.
    at: Mapped[AirTime] = mapped_column(ClockTenths)
    line: Mapped[int] = mapped_column(Integer)
    length: Mapped[SpotLength] = mapped_column(Seconds)
    rate: Mapped[Money] = mapped_column(Cents)
    cart: Mapped[str] = mapped_column(String(CODE_LENGTH), default="")
    moved: Mapped[bool] = mapped_column(Boolean, default=False)

    invoice: Mapped[InvoiceRow] = relationship(back_populates="details")

    def __repr__(self) -> str:
        return f"<InvoiceDetailRow {self.invoice_number} {self.day} {self.at}>"


class InvoiceSummaryRow(Base):
    __tablename__ = "invoice_summary"
    __table_args__ = (
        UniqueConstraint(
            "invoice_number", "line", name="uq_invoice_summary_invoice_number_line"
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    invoice_number: Mapped[str] = mapped_column(
        ForeignKey("invoice.number", ondelete="CASCADE")
    )
    line: Mapped[int] = mapped_column(Integer)
    station: Mapped[str] = mapped_column(String(CODE_LENGTH))
    daypart: Mapped[str] = mapped_column(String(CODE_LENGTH))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    rate: Mapped[Money] = mapped_column(Cents)
    spots: Mapped[int] = mapped_column(Integer)
    gross: Mapped[Money] = mapped_column(Cents)

    invoice: Mapped[InvoiceRow] = relationship(back_populates="summaries")

    def __repr__(self) -> str:
        return f"<InvoiceSummaryRow {self.invoice_number} line {self.line}>"
