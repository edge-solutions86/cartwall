"""Advertisers, agencies, conflict codes and the copy library.

The one shape decision worth defending: **conflict codes are rows, not a comma
separated column.** An advertiser carries several, the log builder asks "who
else is on AUTO" on every placement, and a LIKE against a packed string is both
slow and wrong. ``AUTO`` matches ``AUTOMOTIVE`` under LIKE and does not match
``AUTO.DOMESTIC`` under equality, and the code has to do both jobs.

The rate card is here too, and it is stored the way it was filed rather than
the way it is used. One row per daypart per class, quoting a thirty. The length
factors are not in the database at all: they are a business rule that applies
to every card, and a station that wanted its own would be filing a different
kind of card.
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

from ...airtime import Duration
from ...domain.account import Credit
from ...domain.ratecard import ClassOfTime
from ...domain.spot import SpotLength
from ...money import Money
from ..base import CODE_LENGTH, NAME_LENGTH, Base, Cents, EnumValue, Seconds, Tenths
from .reference import StationRow


class AgencyRow(Base):
    __tablename__ = "agency"

    code: Mapped[str] = mapped_column(String(CODE_LENGTH), primary_key=True)
    name: Mapped[str] = mapped_column(String(NAME_LENGTH))
    rate: Mapped[Decimal] = mapped_column(Numeric(5, 4), default=Decimal("0.15"))
    advertiser_liable: Mapped[bool] = mapped_column(Boolean, default=False)

    def __repr__(self) -> str:
        return f"<AgencyRow {self.code}>"


class AdvertiserRow(Base):
    __tablename__ = "advertiser"

    code: Mapped[str] = mapped_column(String(CODE_LENGTH), primary_key=True)
    name: Mapped[str] = mapped_column(String(NAME_LENGTH))
    agency: Mapped[str | None] = mapped_column(ForeignKey("agency.code"), nullable=True)
    parent: Mapped[str | None] = mapped_column(
        ForeignKey("advertiser.code", name="fk_advertiser_parent_advertiser"), nullable=True
    )
    credit: Mapped[Credit] = mapped_column(EnumValue(Credit), default=Credit.OPEN)

    categories: Mapped[list[CategoryRow]] = relationship(
        back_populates="account",
        cascade="all, delete-orphan",
        order_by="CategoryRow.path",
    )
    cuts: Mapped[list[CutRow]] = relationship(
        back_populates="account", cascade="all, delete-orphan", order_by="CutRow.cart"
    )

    def __repr__(self) -> str:
        return f"<AdvertiserRow {self.code}>"


class CategoryRow(Base):
    __tablename__ = "advertiser_category"
    __table_args__ = (
        UniqueConstraint("advertiser", "path", name="uq_advertiser_category_advertiser_path"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code", ondelete="CASCADE"))
    #: The dotted code, stored whole. ``AUTO.DOMESTIC.FORD``.
    path: Mapped[str] = mapped_column(String(64))
    #: The first segment, so the common "who else is on AUTO" query is indexed.
    root: Mapped[str] = mapped_column(String(32), index=True)

    account: Mapped[AdvertiserRow] = relationship(back_populates="categories")

    def __repr__(self) -> str:
        return f"<CategoryRow {self.advertiser} {self.path}>"


class CutRow(Base):
    __tablename__ = "cut"

    cart: Mapped[str] = mapped_column(String(CODE_LENGTH), primary_key=True)
    isci: Mapped[str] = mapped_column(String(24))
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code", ondelete="CASCADE"))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    #: What it actually runs, in tenths. Not the same number as the length.
    actual: Mapped[Duration] = mapped_column(Tenths)
    first_air: Mapped[date] = mapped_column(Date)
    last_air: Mapped[date | None] = mapped_column(Date, nullable=True)
    killed: Mapped[bool] = mapped_column(Boolean, default=False)

    account: Mapped[AdvertiserRow] = relationship(back_populates="cuts")

    def __repr__(self) -> str:
        return f"<CutRow {self.cart}>"


class RateCardRow(Base):
    __tablename__ = "rate_card"
    __table_args__ = (
        UniqueConstraint("station", "effective", name="uq_rate_card_station_effective"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code: Mapped[str] = mapped_column(String(CODE_LENGTH))
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    effective: Mapped[date] = mapped_column(Date)

    quotes: Mapped[list[QuoteRow]] = relationship(
        back_populates="card", cascade="all, delete-orphan", order_by="QuoteRow.id"
    )
    #: Not for reading. It is here so the unit of work knows a card cannot be
    #: inserted before the station it is filed against.
    signal: Mapped[StationRow] = relationship()

    def __repr__(self) -> str:
        return f"<RateCardRow {self.code}>"


class QuoteRow(Base):
    __tablename__ = "rate_quote"
    __table_args__ = (
        UniqueConstraint("card_id", "daypart", "grade", name="uq_rate_quote_card_id_daypart_grade"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    card_id: Mapped[int] = mapped_column(ForeignKey("rate_card.id", ondelete="CASCADE"))
    daypart: Mapped[str] = mapped_column(String(CODE_LENGTH))
    grade: Mapped[ClassOfTime] = mapped_column(EnumValue(ClassOfTime))
    #: What a thirty costs. Every other length comes off this by a factor.
    thirty: Mapped[Money] = mapped_column(Cents)

    card: Mapped[RateCardRow] = relationship(back_populates="quotes")

    def __repr__(self) -> str:
        return f"<QuoteRow {self.card_id} {self.daypart} {self.grade}>"
