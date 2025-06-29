"""The billing commands, run once a month by a person who is watching.

None of these go in cron. The invoice run is the one job in the building that
somebody signs off before it happens, because it puts numbers in front of
agencies and a wrong one costs a phone call to every account on the list.

    cartwall invoice 2025-08              raise August's invoices
    cartwall invoice 2025-08 --dry-run    say what it would raise
    cartwall credit 25-1001 60.00 ...     write one credit
    cartwall owing                        what is still out

``--dry-run`` is not a flag on the service. The service raises invoices; the
command rolls the transaction back instead of committing it. That is the only
honest way to do a dry run of something with a gapless counter in it: any
version that tries to predict the numbers without taking them will be wrong the
first time two people run it at once.
"""

from __future__ import annotations

import click

from ..broadcast import BroadcastMonth
from ..money import Money
from ..persistence.models.credits import Reason
from ..services.crediting import outstanding, raise_credit, standing
from ..services.invoicing import run_month
from .main import House, cli, pass_house


@cli.command()
@click.argument("month")
@click.option("--on", default=None, help="Date to raise them on. Defaults to today.")
@click.option("--dry-run", is_flag=True, help="Say what it would do and change nothing.")
@pass_house
def invoice(house: House, month: str, on: str | None, dry_run: bool) -> None:
    """Raise the invoices for broadcast MONTH, written as 2025-08."""
    period = BroadcastMonth.parse(month)
    raised_on = _day(on)
    with house.work() as uow:
        report = run_month(uow, period, raised_on)
        click.echo(f"{report.month}  {period.start} to {period.end}")
        for number in report.raised:
            click.echo(f"  raised   {number}")
        for order, why in report.skipped:
            click.echo(f"  skipped  {order} ({why})")
        for order in report.nothing_aired:
            click.echo(f"  nothing  {order}")
        if dry_run:
            click.echo(f"\ndry run, nothing written, {report.count} would be raised")
            return
        uow.commit()
    click.echo(f"\n{report.count} raised")


@cli.command()
@click.argument("invoice_number")
@click.argument("amount")
@click.option(
    "--reason",
    type=click.Choice([reason.name.lower() for reason in Reason]),
    required=True,
    help="Why. The month end report groups by this.",
)
@click.option("--note", default="", help="Needed when the reason is other.")
@click.option("--on", default=None, help="Date to raise it on. Defaults to today.")
@click.option("--spots", type=int, default=0, help="How many spots it covers.")
@pass_house
def credit(
    house: House,
    invoice_number: str,
    amount: str,
    reason: str,
    note: str,
    on: str | None,
    spots: int,
) -> None:
    """Write a credit of AMOUNT against INVOICE_NUMBER."""
    gross = Money.parse(amount)
    why = Reason[reason.upper()]
    with house.work() as uow:
        before = standing(uow, invoice_number)
        row = raise_credit(uow, invoice_number, gross, why, _day(on), note, spots)
        after = standing(uow, invoice_number)
        uow.commit()
    click.echo(f"{row.number} against {row.invoice_number} for {row.reason.value}")
    click.echo(f"  gross      {row.gross}")
    click.echo(f"  commission {row.commission}")
    click.echo(f"  net        {row.net}")
    click.echo(f"  owing      {before.owing} becomes {after.owing}")


@cli.command()
@click.option("--limit", type=int, default=20, help="How many to show.")
@pass_house
def owing(house: House, limit: int) -> None:
    """Every invoice with something still on it, most owing first."""
    with house.work() as uow:
        open_ones = outstanding(uow)
    if not open_ones:
        click.echo("nothing outstanding")
        return
    for entry in open_ones[:limit]:
        click.echo(f"{entry.invoice:<12} {entry.owing!s:>12}  of {entry.billed}")
    total = Money(sum(entry.owing.cents for entry in open_ones))
    click.echo(f"\n{len(open_ones)} invoices, {total} outstanding")


def _day(text: str | None):
    from datetime import date

    from .main import parse_day

    return parse_day(text) if text else date.today()
