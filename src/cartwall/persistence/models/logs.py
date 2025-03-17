"""The log as stored: one row per station day, one row per spot on it.

The air time is stored even though the domain computes it. That looks like
duplication and it is not. The computed time is what the log said when it was
built; the stored time is what the log said when it was released, and a redub
between those two moments changes the first and must not change the second.
When the affidavit is challenged, the stored number is the one that was on the
paper the operator worked from.

Position inside the break is stored explicitly for the same reason air times
were not left to insertion order in the domain. Rows come back from a database
in whatever order it feels like, and "second in the break" is a fact about the
log, not about the query plan.

Bumps are their own table rather than a flag on the spot, because a bumped spot
is not a spot any more. It is a debt. The makegood run reads this table, and a
bump that never turned into a makegood is a credit at the end of the month.
"""

from __future__ import annotations

from datetime import date, datetime

from sqlalchemy import (
    Boolean,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from ...airtime import AirTime
from ...domain.ratecard import ClassOfTime
from ...domain.spot import SpotLength
from ...logs.approval import LogState
from ..base import CODE_LENGTH, NAME_LENGTH, Base, ClockTenths, EnumValue, Seconds
from .reference import StationRow


class LogRow(Base):
    __tablename__ = "air_log"
    __table_args__ = (UniqueConstraint("station", "day", name="uq_air_log_station_day"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    day: Mapped[date] = mapped_column(Date, index=True)
    state: Mapped[LogState] = mapped_column(EnumValue(LogState), default=LogState.DRAFT)
    revision: Mapped[int] = mapped_column(Integer, default=0)
    approved_by: Mapped[str] = mapped_column(String(NAME_LENGTH), default="")
    approved_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    released_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    spots: Mapped[list[SpotRow]] = relationship(
        back_populates="log",
        cascade="all, delete-orphan",
        order_by="(SpotRow.hour, SpotRow.break_number, SpotRow.position)",
    )
    signal: Mapped[StationRow] = relationship()

    def __repr__(self) -> str:
        return f"<LogRow {self.station} {self.day} rev {self.revision}>"


class SpotRow(Base):
    __tablename__ = "air_spot"
    __table_args__ = (
        UniqueConstraint(
            "log_id", "hour", "break_number", "position",
            name="uq_air_spot_log_id_hour_break_number_position",
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    log_id: Mapped[int] = mapped_column(ForeignKey("air_log.id", ondelete="CASCADE"))
    hour: Mapped[int] = mapped_column(Integer)
    break_number: Mapped[int] = mapped_column(Integer)
    #: Where in the break, counting from zero. Not the query plan's business.
    position: Mapped[int] = mapped_column(Integer)
    #: What the log said when it went out. Not recomputed on read.
    at: Mapped[AirTime] = mapped_column(ClockTenths)

    order_number: Mapped[str] = mapped_column(String(24))
    line: Mapped[int] = mapped_column(Integer)
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code"))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    grade: Mapped[ClassOfTime] = mapped_column(EnumValue(ClassOfTime))
    cart: Mapped[str] = mapped_column(String(CODE_LENGTH), default="")
    fixed: Mapped[bool] = mapped_column(Boolean, default=False)

    log: Mapped[LogRow] = relationship(back_populates="spots")

    @property
    def key(self) -> tuple[str, int]:
        return (self.order_number, self.line)

    def __repr__(self) -> str:
        return f"<SpotRow {self.order_number}-{self.line} {self.at}>"


class BumpRow(Base):
    __tablename__ = "bump"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    station: Mapped[str] = mapped_column(ForeignKey("station.call"))
    day: Mapped[date] = mapped_column(Date, index=True)
    hour: Mapped[int] = mapped_column(Integer)
    break_number: Mapped[int] = mapped_column(Integer)

    order_number: Mapped[str] = mapped_column(String(24), index=True)
    line: Mapped[int] = mapped_column(Integer)
    advertiser: Mapped[str] = mapped_column(ForeignKey("advertiser.code"))
    length: Mapped[SpotLength] = mapped_column(Seconds)
    grade: Mapped[ClassOfTime] = mapped_column(EnumValue(ClassOfTime))

    #: Who took the unit.
    by_order: Mapped[str] = mapped_column(String(24))
    by_line: Mapped[int] = mapped_column(Integer)
    #: Set when a makegood has been offered and taken for this bump.
    made_good: Mapped[bool] = mapped_column(Boolean, default=False)

    signal: Mapped[StationRow] = relationship()

    @property
    def key(self) -> tuple[str, int]:
        return (self.order_number, self.line)

    def __repr__(self) -> str:
        return f"<BumpRow {self.order_number}-{self.line} off {self.day}>"
