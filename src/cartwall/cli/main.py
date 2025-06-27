"""The command line, which is what actually runs at four in the morning.

The HTTP surface is for people. This is for cron and for the traffic director
when the web thing is not answering, and it is built for that:

* **Everything writes to standard output and nothing to standard error unless
  it failed.** A cron job that prints on success sends mail every night and
  gets filtered, which is how a real failure goes unread for a month.
* **The exit code is the answer.** Zero means it worked. One means a refusal
  the operator can act on. Two means the arguments were wrong. Nothing else.
* **Every destructive command asks**, unless ``--yes`` is given. The ones that
  cron runs are not destructive, so cron never needs the flag.
* **The package's own logging is off** while a command runs. The command prints
  what it did in its own words; the same facts twice in two formats is worse
  than either alone.
"""

from __future__ import annotations

import sys
from datetime import date

import click

from .. import __version__
from ..config import Settings
from ..errors import CartwallError
from ..logging import quiet
from ..persistence.base import create_all, drop_all
from ..persistence.unitofwork import open_database, unit_of_work

# Importing the command modules is what registers them on the group.

#: What the shell gets back. Anything else is a bug.
OK = 0
REFUSED = 1
MISUSED = 2


class House:
    """What every command needs: settings, and a database when it wants one."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._engine = None

    @property
    def engine(self):
        if self._engine is None:
            self._engine = open_database(self.settings)
        return self._engine

    def work(self):
        return unit_of_work(self.engine)


pass_house = click.make_pass_decorator(House)


class HouseGroup(click.Group):
    """A group that turns the package's refusals into the house error shape.

    This has to live on the group rather than in ``run``, because the test
    runner and any other embedder invoke the group directly and would
    otherwise see a bare traceback where an operator sees a sentence.
    """

    def invoke(self, context: click.Context):
        try:
            return super().invoke(context)
        except CartwallError as trouble:
            raise click.ClickException(f"{trouble.code}: {trouble.message}") from None


@click.group(cls=HouseGroup, context_settings={"help_option_names": ["-h", "--help"]})
@click.option("--database", "-d", default=None, help="Database URL. Beats the environment.")
@click.version_option(__version__, "--version", "-V")
@click.pass_context
def cli(context: click.Context, database: str | None) -> None:
    """Traffic, continuity and billing for a radio group."""
    settings = Settings.from_environment()
    if database:
        settings = settings.with_database(database)
    context.obj = House(settings)


@cli.command("init-db")
@click.option("--yes", is_flag=True, help="Do not ask.")
@pass_house
def init_db(house: House, yes: bool) -> None:
    """Build the schema in an empty database."""
    if not yes:
        click.confirm(f"build the schema in {house.settings.database_url}?", abort=True)
    create_all(house.engine)
    click.echo(f"schema built in {house.settings.database_url}")


@cli.command("drop-db")
@click.option("--yes", is_flag=True, help="Do not ask.")
@pass_house
def drop_db(house: House, yes: bool) -> None:
    """Take the schema out. Everything in it goes with it."""
    if not yes:
        click.confirm(f"DROP every table in {house.settings.database_url}?", abort=True)
    drop_all(house.engine)
    click.echo(f"schema dropped from {house.settings.database_url}")


@cli.command()
@pass_house
def status(house: House) -> None:
    """What this process is pointed at, and whether it answers."""
    click.echo(f"market    {house.settings.market}")
    click.echo(f"database  {house.settings.database_url}")
    click.echo(f"horizon   {house.settings.horizon_days} days")
    try:
        with house.engine.connect() as connection:
            connection.exec_driver_sql("SELECT 1")
    except Exception as trouble:
        click.echo(f"database  unreachable: {trouble}", err=True)
        raise SystemExit(REFUSED) from None
    click.echo("database  answering")


@cli.command()
@pass_house
def stations(house: House) -> None:
    """The signals on file, and which of them get a log."""
    with house.work() as uow:
        cluster = uow.stations.cluster()
        if not len(cluster):
            click.echo("no stations on file")
            return
        for station in cluster:
            carried = f" carries {station.carries}" if station.carries else ""
            logged = "" if station.is_satellite else " *"
            click.echo(
                f"{station.call:<10} {station.dial:<10} {station.service.value:<10}"
                f"{carried}{logged}"
            )
        click.echo(f"\n{len(cluster.logged)} of {len(cluster)} get a log, marked *")


def parse_day(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError:
        raise click.BadParameter(f"{text!r} is not a date, use YYYY-MM-DD") from None


def run(argv: list[str] | None = None) -> int:
    """The entry point, with the house rules about output and exit codes."""
    with quiet():
        try:
            cli.main(args=argv, standalone_mode=False)
        except click.Abort:
            click.echo("stopped", err=True)
            return REFUSED
        except click.UsageError as trouble:
            click.echo(str(trouble), err=True)
            return MISUSED
        except click.ClickException as trouble:
            # Everything the group turned into one, which is every refusal.
            click.echo(str(trouble), err=True)
            return REFUSED
        except SystemExit as leaving:
            return int(leaving.code or OK)
    return OK


def main() -> None:  # pragma: no cover - the console script
    sys.exit(run())


def load_commands() -> None:
    """Register every command module on the group. Called at import time."""
    from . import logs  # noqa: F401  registering is the whole point


load_commands()
