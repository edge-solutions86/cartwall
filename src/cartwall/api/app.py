"""The application factory, and the state one process shares.

There is one factory rather than a module level ``app`` because the tests build
several applications against several databases, and a module level application
means a module level engine, which means the first import decides what the
whole test run talks to.

The engine hangs off ``app.state``. It is the only shared thing: sessions are
per request, repositories are per session, and nothing caches a domain object
between requests. The rule book for one station on one date is cheap to build
and expensive to have stale, and traffic runs for weeks without a restart.
"""

from __future__ import annotations

from fastapi import FastAPI
from sqlalchemy.engine import Engine

from .. import __version__
from ..config import Settings
from ..persistence.unitofwork import open_database
from . import errors
from .routers import accounts, avails, logs, orders, reconcile
from .security import KeyRing

TITLE = "cartwall"
DESCRIPTION = "Traffic, continuity and billing for a radio group"


def create_app(
    settings: Settings | None = None,
    engine: Engine | None = None,
    keys: KeyRing | None = None,
) -> FastAPI:
    """Build an application. Hand it an engine in tests, a settings elsewhere."""
    settings = settings or Settings.from_environment()
    app = FastAPI(
        title=TITLE,
        description=DESCRIPTION,
        version=__version__,
        docs_url="/docs",
        openapi_url="/openapi.json",
    )
    app.state.settings = settings
    app.state.engine = engine if engine is not None else open_database(settings)
    app.state.keys = keys if keys is not None else KeyRing.from_environment()
    errors.install(app)

    # Health and readiness are outside the gate. A monitor that has to hold a
    # key is a monitor somebody eventually turns off.
    @app.get("/health", tags=["service"])
    def health() -> dict[str, str]:
        """Is the process up. Does not touch the database, on purpose."""
        return {"status": "up", "version": __version__, "market": settings.market}

    @app.get("/ready", tags=["service"])
    def ready() -> dict[str, str]:
        """Is the database there. This one does touch it."""
        with app.state.engine.connect() as connection:
            connection.exec_driver_sql("SELECT 1")
        return {"status": "ready"}

    app.include_router(orders.router)
    app.include_router(logs.router)
    app.include_router(avails.router)
    app.include_router(accounts.router)
    app.include_router(reconcile.router)
    return app
