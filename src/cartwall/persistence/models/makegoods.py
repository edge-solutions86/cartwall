"""The makegood ledger on disk: what is owed, and what was offered back.

Two tables, because a debt and an offer have different lifetimes. A debt is
raised the night the as-run says a spot did not run, and it stays raised until
something makes it good. An offer is a conversation with a client that may be
refused, may expire, and may be followed by another offer against the same
debt. Folding them into one row would mean a refused offer either destroys the
debt or leaves it looking settled, and both have been somebody's bad month.

**A debt is unique on line, day, cause and sequence.** The first three are the
obvious part: the same spot can be owed twice on one day, once because the
as-run says it errored and once because a political order bumped a different
airing, and those are two spots to give back rather than one.

The sequence is the part that had to be learned. A line that asked for five
spots in a week and got one is owed **four** spots, and the gather reports them
as four debts that are identical in every other column, because there is
nothing to tell them apart. Without an ordinal all four collapse into one row
and the client is quietly owed a quarter of what they are actually owed.

The ordinal is assigned by counting identical debts within one gather, so the
same span gathered again numbers them the same way and re-running the nightly
job stays a no-op. It is not an air order and it means nothing on its own.

The rate is stored on the debt rather than being looked up from the line. What
a spot was worth is what it was worth on the day it was missed. Rate cards move
and orders get revised, and a debt raised in August has to still be worth its
August rate when somebody clears it in November.

Offers point at the debt by id. There is no natural key for an offer worth
having: the same daypart on the same day can be offered twice if the first was
refused, and inventing a composite that includes the state would put a mutable
column in a primary key.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import Boolean, Date, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ...domain.ratecard import ClassOfTime
from ...domain.spot import SpotLength
from ...money import Money
from ...services.makegood import Cause, Offer
from ..base import CODE_LENGTH, Base, Cents, EnumValue, Seconds

#: Long enough for a daypart label, which is three or four characters.
LABEL_LENGTH = 16


class DebtRow(Base):
    """One spot owed to one line, on one day, for one reason."""

    __tablename__ = "makegood_debt"
    __table_args__ = (
        UniqueConstraint(
            "order_number",
            "line",
            "day",
            "cause",
            "sequence",
            name="uq_makegood_debt_line_day_cause_sequence",
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    order_number: Mapped[str] = mapped_column(ForeignKey("sales_order.number"), index=True)
    line: Mapped[int] = mapped_column(Integer)
    advertiser: Mapped[str] = mapped_column(String(CODE_LENGTH))
    station: Mapped[str] = mapped_column(String(CODE_LENGTH), index=True)
    daypart: Mapped[str] = mapped_column(String(LABEL_LENGTH))
    grade: Mapped[ClassOfTime] = mapped_column(EnumValue(ClassOfTime))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    #: What the spot was worth on the day it was missed, not today.
    rate: Mapped[Money] = mapped_column(Cents)
    day: Mapped[date] = mapped_column(Date, index=True)
    cause: Mapped[Cause] = mapped_column(EnumValue(Cause))
    #: Which of several identical debts this is. Counted, never meaningful.
    sequence: Mapped[int] = mapped_column(Integer, default=1)

    offers: Mapped[list[OfferRow]] = relationship(
        back_populates="debt", cascade="all, delete-orphan"
    )

    def __repr__(self) -> str:
        return (
            f"<DebtRow {self.order_number}-{self.line} {self.day} "
            f"{self.cause.value} #{self.sequence}>"
        )


class OfferRow(Base):
    """One makegood proposed against one debt."""

    __tablename__ = "makegood_offer"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    debt_id: Mapped[int] = mapped_column(ForeignKey("makegood_debt.id"), index=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    daypart: Mapped[str] = mapped_column(String(LABEL_LENGTH))
    rate: Mapped[Money] = mapped_column(Cents)
    state: Mapped[Offer] = mapped_column(EnumValue(Offer), default=Offer.OPEN)
    #: Worth less than what it replaces. Set once, when the offer is made.
    short: Mapped[bool] = mapped_column(Boolean, default=False)

    debt: Mapped[DebtRow] = relationship(back_populates="offers")

    def __repr__(self) -> str:
        return f"<OfferRow {self.daypart} on {self.day} ({self.state.value})>"
