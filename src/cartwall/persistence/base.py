"""The database floor: one base, one naming convention, and honest columns.

Two decisions worth stating before any table appears.

**Every constraint is named.** SQLite and Postgres invent different names for
an unnamed unique constraint, and alembic then generates a migration that works
on the developer's laptop and fails on the server. The convention below makes
the name a function of the columns, so it is the same everywhere and a
migration that drops a constraint can name it.

**Nothing is stored in a type that loses information.** Money is an integer
number of cents, never a float and never a numeric that somebody will
eventually read into a float. Durations are integer tenths. Air times are
integer tenths from midnight rather than a TIME, because a TIME column invites
a timezone and this domain has no timezone: 7:10 in the log means 7:10 on the
transmitter, whatever the server thinks the hour is.

Enumerations are stored as their short string value, not as a database enum
type and not as an ordinal. Adding a class of time should not be a migration
on Postgres, and an ordinal makes the rows unreadable in a console at two in
the morning, which is when somebody reads them.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import Integer, MetaData, String, TypeDecorator, create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker

from ..airtime import AirTime, Duration
from ..money import Money

NAMING = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_N_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

#: Long enough for a campaign name, short enough that nobody pastes an essay.
NAME_LENGTH = 120
CODE_LENGTH = 16


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING)


class Cents(TypeDecorator):
    """Money, stored as a whole number of cents."""

    impl = Integer
    cache_ok = True

    def process_bind_param(self, value: Money | None, dialect) -> int | None:
        if value is None:
            return None
        return value.cents

    def process_result_value(self, value: int | None, dialect) -> Money | None:
        if value is None:
            return None
        return Money(value)


class Tenths(TypeDecorator):
    """A length, stored as a whole number of tenths of a second."""

    impl = Integer
    cache_ok = True

    def process_bind_param(self, value: Duration | None, dialect) -> int | None:
        if value is None:
            return None
        return value.tenths

    def process_result_value(self, value: int | None, dialect) -> Duration | None:
        if value is None:
            return None
        return Duration(value)


class ClockTenths(TypeDecorator):
    """An air time, stored as tenths from midnight. No timezone, on purpose."""

    impl = Integer
    cache_ok = True

    def process_bind_param(self, value: AirTime | None, dialect) -> int | None:
        if value is None:
            return None
        return value.tenths

    def process_result_value(self, value: int | None, dialect) -> AirTime | None:
        if value is None:
            return None
        return AirTime(value)


class EnumValue(TypeDecorator):
    """An enumeration, stored as its short string value."""

    impl = String(32)
    cache_ok = True

    def __init__(self, enum_type, *args, **kw) -> None:
        self.enum_type = enum_type
        super().__init__(*args, **kw)

    def process_bind_param(self, value, dialect) -> str | None:
        if value is None:
            return None
        return value.value

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        return self.enum_type(value)


def engine_for(url: str, echo: bool = False) -> Engine:
    """A connected engine with SQLite told to behave itself.

    SQLite does not enforce foreign keys unless it is asked to, once per
    connection. A test suite that skips this passes against constraints that
    the server would have refused.
    """
    engine = create_engine(url, echo=echo, future=True)
    if engine.dialect.name == "sqlite":
        from sqlalchemy import event

        @event.listens_for(engine, "connect")
        def _keys_on(connection, record):  # pragma: no cover - driver callback
            cursor = connection.cursor()
            cursor.execute("PRAGMA foreign_keys=ON")
            cursor.close()

    return engine


def session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, future=True)


def create_all(engine: Engine) -> None:
    Base.metadata.create_all(engine)


def drop_all(engine: Engine) -> None:
    Base.metadata.drop_all(engine)


def as_date(value: date | str) -> date:
    """Dates arrive from JSON as strings often enough to be worth one helper."""
    if isinstance(value, date):
        return value
    return date.fromisoformat(value)
