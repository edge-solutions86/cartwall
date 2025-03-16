"""Orders, the lines on them, and the copy rotation each line runs.

The flight lives on the line rather than in a table of its own. It has no
identity apart from the line, nobody queries flights, and splitting it out
would buy one join on every read of an order for nothing.

What does get its own table is the **rotation**, because a line has several
carts with weights and that is a list. It also changes independently: killing
one cut mid flight rewrites the rotation and touches nothing else on the order.

The rate is stored on the line and not looked up from the card at read time.
That is deliberate duplication. The card can be superseded, the order was
written at a number both sides agreed to, and the invoice has to bill what was
agreed rather than what the current card says. ``card_code`` records which card
the number came off so anybody can check the arithmetic later.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import (
    Date,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ...domain.order import OrderStatus
from ...domain.ratecard import ClassOfTime
from ...domain.rotation import RotationKind
from ...domain.spot import SpotLength
from ...money import Money
from ..base import CODE_LENGTH, NAME_LENGTH, Base, Cents, EnumValue, Seconds
from .accounts import AdvertiserRow
from .reference import StationRow


class OrderRow(Base):
    __tablename__ = "sales_order"

    number: Mapped[str] = mapped_column(String(24), primary_key=True)
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code"))
    agency: Mapped[str | None] = mapped_column(ForeignKey("agency.code"), nullable=True)
    written: Mapped[date] = mapped_column(Date)
    salesperson: Mapped[str] = mapped_column(String(CODE_LENGTH))
    campaign: Mapped[str] = mapped_column(String(NAME_LENGTH), default="")
    status: Mapped[OrderStatus] = mapped_column(
        EnumValue(OrderStatus), default=OrderStatus.PENDING
    )

    lines: Mapped[list[LineRow]] = relationship(
        back_populates="order",
        cascade="all, delete-orphan",
        order_by="LineRow.number",
    )
    account: Mapped[AdvertiserRow] = relationship()

    def __repr__(self) -> str:
        return f"<OrderRow {self.number}>"


class LineRow(Base):
    __tablename__ = "sales_line"
    __table_args__ = (
        UniqueConstraint("order_number", "number", name="uq_sales_line_order_number_number"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    order_number: Mapped[str] = mapped_column(
        ForeignKey("sales_order.number", ondelete="CASCADE")
    )
    number: Mapped[int] = mapped_column(Integer)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    daypart: Mapped[str] = mapped_column(String(CODE_LENGTH))
    grade: Mapped[ClassOfTime] = mapped_column(EnumValue(ClassOfTime))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    rate: Mapped[Money] = mapped_column(Cents)
    #: Which card the rate came off, so the number can be checked later.
    card_code: Mapped[str] = mapped_column(String(CODE_LENGTH), default="")

    first_air: Mapped[date] = mapped_column(Date)
    last_air: Mapped[date] = mapped_column(Date)
    #: ``M-F``, ``Sa,Su``. Stored the way it was sold.
    days: Mapped[str] = mapped_column(String(32))
    per_week: Mapped[int] = mapped_column(Integer)

    order: Mapped[OrderRow] = relationship(back_populates="lines")
    signal: Mapped[StationRow] = relationship()
    rotation: Mapped[list[RotationRow]] = relationship(
        back_populates="line",
        cascade="all, delete-orphan",
        order_by="RotationRow.position",
    )

    def __repr__(self) -> str:
        return f"<LineRow {self.order_number}-{self.number}>"


class RotationRow(Base):
    __tablename__ = "line_rotation"
    __table_args__ = (
        UniqueConstraint("line_id", "cart", name="uq_line_rotation_line_id_cart"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    line_id: Mapped[int] = mapped_column(ForeignKey("sales_line.id", ondelete="CASCADE"))
    #: Where this cart sits in the rotation as written. Sequential rotations
    #: run in this order and the others only use it to break ties.
    position: Mapped[int] = mapped_column(Integer)
    cart: Mapped[str] = mapped_column(ForeignKey("cut.cart"))
    weight: Mapped[int] = mapped_column(Integer, default=1)
    kind: Mapped[RotationKind] = mapped_column(
        EnumValue(RotationKind), default=RotationKind.EVEN
    )

    line: Mapped[LineRow] = relationship(back_populates="rotation")

    def __repr__(self) -> str:
        return f"<RotationRow {self.line_id} {self.cart}>"
