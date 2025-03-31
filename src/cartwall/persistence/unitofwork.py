"""One session, one transaction, and every repository hanging off it.

A unit of work is a context manager, and leaving the block without committing
rolls back. That is the default on purpose. Traffic runs a lot of jobs that
read, decide, and then decide not to write, and the failure mode of an implicit
commit is a half built log that looks finished.

    with unit_of_work(engine) as uow:
        order = uow.orders.get("25-1044")
        uow.orders.save(order.confirmed())
        uow.commit()

The repositories share the session, so anything one of them writes is visible
to the next without a commit. That is the whole reason they are gathered here
rather than being constructed where they are used.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager

from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session

from ..config import Settings
from ..errors import ValidationError
from .base import create_all, engine_for, session_factory
from .repositories import (
    AccountRepository,
    OrderRepository,
    RateCardRepository,
    StationRepository,
)


class UnitOfWorkError(ValidationError):
    """A unit of work used after it was finished with."""

    code = "bad_unit_of_work"


class UnitOfWork:
    """Everything one transaction can reach."""

    def __init__(self, session: Session) -> None:
        self.session = session
        self.stations = StationRepository(session)
        self.accounts = AccountRepository(session)
        self.cards = RateCardRepository(session)
        self.orders = OrderRepository(session)
        self._closed = False
        self._committed = False

    @property
    def committed(self) -> bool:
        return self._committed

    def _check_open(self) -> None:
        if self._closed:
            raise UnitOfWorkError("this unit of work has already been closed")

    def flush(self) -> None:
        self._check_open()
        self.session.flush()

    def commit(self) -> None:
        self._check_open()
        self.session.commit()
        self._committed = True

    def rollback(self) -> None:
        self._check_open()
        self.session.rollback()
        self._committed = False

    def close(self) -> None:
        if not self._closed:
            self.session.close()
            self._closed = True


@contextmanager
def unit_of_work(engine: Engine) -> Iterator[UnitOfWork]:
    """Roll back unless somebody says otherwise."""
    factory = session_factory(engine)
    session = factory()
    uow = UnitOfWork(session)
    try:
        yield uow
        if not uow.committed:
            uow.rollback()
    except Exception:
        uow.rollback()
        raise
    finally:
        uow.close()


def open_database(settings: Settings, build: bool = False) -> Engine:
    """Connect, and optionally put the schema in a database that has none."""
    engine = engine_for(settings.database_url, echo=settings.echo_sql)
    if build:
        create_all(engine)
    return engine
