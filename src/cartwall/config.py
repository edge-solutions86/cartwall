"""Settings, read from the environment once and then left alone.

Everything is read at start up and nothing reads the environment again. A
process whose behaviour changes because somebody exported a variable while it
was running is a process nobody can reason about, and traffic runs unattended
overnight.

The station group is small enough that the only genuinely deployment specific
setting is the database. Everything else here has a default that is right for
the house and wrong for nobody, and is present because it has been wanted at
least once rather than because a settings file ought to have things in it.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

from .errors import ValidationError

PREFIX = "CARTWALL_"

DEFAULT_URL = "sqlite+pysqlite:///cartwall.sqlite3"

#: How far ahead logs are built. Two weeks is what the automation will hold.
DEFAULT_HORIZON_DAYS = 14


class ConfigError(ValidationError):
    """A setting that cannot be used."""

    code = "bad_config"


def _flag(name: str, fallback: bool) -> bool:
    raw = os.environ.get(PREFIX + name)
    if raw is None:
        return fallback
    lowered = raw.strip().lower()
    if lowered in ("1", "true", "yes", "on"):
        return True
    if lowered in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{PREFIX}{name} is {raw!r}, which is not a yes or a no")


def _number(name: str, fallback: int) -> int:
    raw = os.environ.get(PREFIX + name)
    if raw is None:
        return fallback
    try:
        return int(raw)
    except ValueError:
        raise ConfigError(f"{PREFIX}{name} is {raw!r}, which is not a number") from None


@dataclass(frozen=True, slots=True)
class Settings:
    """Everything the process needs to know that is not in the database."""

    database_url: str = DEFAULT_URL
    #: The house, printed on paper and used to pick the cluster.
    market: str = "Waterloo Cedar Falls"
    #: How many days ahead the log builder runs.
    horizon_days: int = DEFAULT_HORIZON_DAYS
    #: Echo SQL. Useful once a year and painful the rest of the time.
    echo_sql: bool = False
    #: Refuse to release a log with any spot missing copy. Off only for tests.
    strict_copy: bool = True

    def __post_init__(self) -> None:
        if not self.database_url:
            raise ConfigError("no database was configured")
        if self.horizon_days < 1:
            raise ConfigError(f"a horizon of {self.horizon_days} days is not a horizon")
        if self.horizon_days > 120:
            raise ConfigError(
                f"a horizon of {self.horizon_days} days is further out than any card is filed"
            )

    @classmethod
    def from_environment(cls) -> Settings:
        return cls(
            database_url=os.environ.get(PREFIX + "DATABASE_URL", DEFAULT_URL),
            market=os.environ.get(PREFIX + "MARKET", "Waterloo Cedar Falls"),
            horizon_days=_number("HORIZON_DAYS", DEFAULT_HORIZON_DAYS),
            echo_sql=_flag("ECHO_SQL", False),
            strict_copy=_flag("STRICT_COPY", True),
        )

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    def with_database(self, url: str) -> Settings:
        return Settings(
            database_url=url,
            market=self.market,
            horizon_days=self.horizon_days,
            echo_sql=self.echo_sql,
            strict_copy=self.strict_copy,
        )

    def __str__(self) -> str:
        return f"{self.market} on {self.database_url}"
