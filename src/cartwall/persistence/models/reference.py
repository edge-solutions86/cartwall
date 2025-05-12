"""The tables that describe the station rather than the business it does.

Signals, dayparts, clocks and the breaks inside them. These change a few times
a year and everything else points at them, which is why they are separate from
the tables that change hourly.

Two things here are not obvious from the shape:

**Clocks and grids are versioned, not edited.** A clock row is never updated
once a log has been built from it, because the affidavit for last Tuesday has
to be reconstructible and the clock is part of that. A change is a new row with
a later effective date. That is also why the primary keys are surrogate
integers rather than the codes: the same code exists several times over.

**Every child table has a relationship back to the station**, and not only a
foreign key column. A bare column tells the database about the dependency and
tells the ORM nothing, so a flush that adds a station and a daypart together
inserts them in the order the mappers happened to be configured, which is the
wrong way round about half the time. The relationship is what makes the unit of
work order the inserts.

**Assignment order is stored explicitly.** The grid resolves an hour by taking
the last assignment filed that covers it, and "last" has to survive a round
trip through a database that has no opinion about row order. The ``ordinal``
column is that order, and reading the assignments without sorting by it will
schedule spots into a ballgame.
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
from ...domain.clock import BreakKind
from ...domain.programming import Source
from ...domain.station import Band, Service
from ..base import CODE_LENGTH, NAME_LENGTH, Base, EnumValue, Tenths


class StationRow(Base):
    __tablename__ = "station"

    call: Mapped[str] = mapped_column(String(CODE_LENGTH), primary_key=True)
    band: Mapped[Band] = mapped_column(EnumValue(Band))
    frequency: Mapped[Decimal] = mapped_column(Numeric(6, 1))
    city_of_license: Mapped[str] = mapped_column(String(NAME_LENGTH))
    service: Mapped[Service] = mapped_column(EnumValue(Service))
    market: Mapped[str] = mapped_column(String(NAME_LENGTH))
    daytimer: Mapped[bool] = mapped_column(Boolean, default=False)
    carries: Mapped[str | None] = mapped_column(
        ForeignKey("station.call", name="fk_station_carries_station"), nullable=True
    )
    commercial_load: Mapped[int] = mapped_column(Integer, default=12)

    dayparts: Mapped[list[DaypartRow]] = relationship(
        back_populates="signal", cascade="all, delete-orphan"
    )
    clocks: Mapped[list[ClockRow]] = relationship(
        back_populates="signal", cascade="all, delete-orphan"
    )
    assignments: Mapped[list[AssignmentRow]] = relationship(
        back_populates="signal",
        cascade="all, delete-orphan",
        order_by="AssignmentRow.ordinal",
    )

    def __repr__(self) -> str:
        return f"<StationRow {self.call}>"


class DaypartRow(Base):
    __tablename__ = "daypart"
    __table_args__ = (UniqueConstraint("station", "code", name="uq_daypart_station_code"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    code: Mapped[str] = mapped_column(String(CODE_LENGTH))
    name: Mapped[str] = mapped_column(String(NAME_LENGTH))
    #: ``M-F``, ``Sa,Su``. Parsed by the domain, stored the way it was typed.
    days: Mapped[str] = mapped_column(String(32))
    first_hour: Mapped[int] = mapped_column(Integer)
    last_hour: Mapped[int] = mapped_column(Integer)

    signal: Mapped[StationRow] = relationship(back_populates="dayparts")

    def __repr__(self) -> str:
        return f"<DaypartRow {self.station} {self.code}>"


class ClockRow(Base):
    __tablename__ = "clock"
    __table_args__ = (
        UniqueConstraint("station", "code", "effective", name="uq_clock_station_code_effective"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    code: Mapped[str] = mapped_column(String(CODE_LENGTH))
    name: Mapped[str] = mapped_column(String(NAME_LENGTH))
    effective: Mapped[date] = mapped_column(Date)

    breaks: Mapped[list[BreakRow]] = relationship(
        back_populates="clock",
        cascade="all, delete-orphan",
        order_by="BreakRow.number",
    )
    signal: Mapped[StationRow] = relationship(back_populates="clocks")

    def __repr__(self) -> str:
        return f"<ClockRow {self.station} {self.code} {self.effective}>"


class BreakRow(Base):
    __tablename__ = "clock_break"
    __table_args__ = (
        UniqueConstraint("clock_id", "number", name="uq_clock_break_clock_id_number"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    clock_id: Mapped[int] = mapped_column(ForeignKey("clock.id", ondelete="CASCADE"))
    number: Mapped[int] = mapped_column(Integer)
    kind: Mapped[BreakKind] = mapped_column(EnumValue(BreakKind))
    position: Mapped[Duration] = mapped_column(Tenths)
    length: Mapped[Duration] = mapped_column(Tenths)
    units: Mapped[int] = mapped_column(Integer)
    paid: Mapped[bool] = mapped_column(Boolean, default=True)

    clock: Mapped[ClockRow] = relationship(back_populates="breaks")

    def __repr__(self) -> str:
        return f"<BreakRow {self.clock_id}/{self.number}>"


class AssignmentRow(Base):
    __tablename__ = "clock_assignment"
    __table_args__ = (
        UniqueConstraint(
            "station", "effective", "ordinal", name="uq_clock_assignment_station_effective_ordinal"
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    effective: Mapped[date] = mapped_column(Date)
    #: Where this sits in the stack. Higher wins. Never read these unsorted.
    ordinal: Mapped[int] = mapped_column(Integer)
    label: Mapped[str] = mapped_column(String(NAME_LENGTH))
    days: Mapped[str] = mapped_column(String(32))
    first_hour: Mapped[int] = mapped_column(Integer)
    last_hour: Mapped[int] = mapped_column(Integer)
    clock_code: Mapped[str] = mapped_column(String(CODE_LENGTH), default="")
    source: Mapped[Source] = mapped_column(EnumValue(Source), default=Source.LOCAL)

    signal: Mapped[StationRow] = relationship(back_populates="assignments")

    def __repr__(self) -> str:
        return f"<AssignmentRow {self.station} {self.ordinal} {self.label}>"
