"""What a route asks for, and where it comes from.

One dependency does the work: a unit of work per request, committed if the
handler returns and rolled back if it raises. Handlers do not commit. A handler
that commits halfway and then fails leaves half a change behind, and the point
of the transaction is that this cannot happen.

Reads go through the same dependency. Committing a transaction that only read
costs nothing and means there is one code path rather than two, and two paths
is how a write ends up on the read one.
"""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Request
from sqlalchemy.engine import Engine

from ..config import Settings
from ..persistence.base import session_factory
from ..persistence.unitofwork import UnitOfWork


def get_engine(request: Request) -> Engine:
    return request.app.state.engine


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_uow(request: Request) -> Iterator[UnitOfWork]:
    """A transaction for one request. Commits on the way out, rolls back on a raise."""
    factory = session_factory(request.app.state.engine)
    session = factory()
    uow = UnitOfWork(session)
    try:
        yield uow
        uow.commit()
    except Exception:
        uow.rollback()
        raise
    finally:
        uow.close()


Work = Depends(get_uow)
Config = Depends(get_settings)
