from datetime import date, datetime

import pytest
from click.testing import CliRunner

from cartwall.airtime import Duration
from cartwall.cli.main import REFUSED, cli
from cartwall.domain.daypart import standard_grid
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY, Cut
from cartwall.logs.day import DayLog
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.models.billing import InvoiceRow
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import post
from cartwall.scheduling.inventory import DayBoard, Placement
from support import CARD_DATE, MONDAY, a_grid

FIXED = ClassOfTime.FIXED
FRIDAY = date(2025, 8, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def url(tmp_path, cluster, accounts, cards, programming):
    url = f"sqlite+pysqlite:///{tmp_path / 'clibill.sqlite3'}"
    engine = engine_for(url)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.cards.file(cards.in_force("KWLO", CARD_DATE))
        uow.stations.save_dayparts("KWLO", standard_grid())
        uow.stations.save_grid(programming["KWLO"])
        uow.accounts.file_cut(
            Cut(
                cart="C1204",
                isci="HYVE2501",
                advertiser="HYVEE",
                length=THIRTY,
                actual=Duration(300),
                first_air=MONDAY,
            )
        )
        uow.commit()
    return url


def billed(url, advertiser="HYVEE", agency=None):
    """One order, one aired spot, ready for the invoice run to find."""
    engine = engine_for(url)
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(
            Order(
                number="25-1044",
                advertiser=advertiser,
                written=date(2025, 7, 21),
                salesperson="DHOLM",
                agency=agency,
                lines=(
                    Line(
                        number=1,
                        station="KWLO",
                        daypart="AMD",
                        grade=FIXED,
                        length=THIRTY,
                        rate=Money.dollars(60),
                        flight=Flight.build(MONDAY, FRIDAY, "M-F", 1),
                        carts=("C1204",),
                    ),
                ),
            ).confirmed()
        )
        board = DayBoard.build(grid, MONDAY)
        board.avail(7, 2).add(
            Placement(
                order="25-1044",
                line=1,
                advertiser=advertiser,
                length=THIRTY,
                grade=FIXED,
                cart="C1204",
            )
        )
        logs = LogRepository(uow.session)
        logs.save(board)
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
        logs.release("KWLO", MONDAY, SIGNED)
        logs.mark_aired("KWLO", MONDAY)
        rows = parse(HEADER + "\n2025-08-04,07:10:00,C1204,t,30.0,played,25-1044-1\n")
        post(uow.session, reconcile(DayLog.build(grid, board), rows))
        uow.commit()


def test_a_month_with_nothing_aired_raises_nothing(runner, url):
    result = runner.invoke(cli, ["-d", url, "invoice", "2025-08"])
    assert result.exit_code == 0
    assert "0 raised" in result.output


def test_the_invoice_run_names_what_it_raised(runner, url):
    billed(url)
    result = runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    assert result.exit_code == 0
    assert "2025-08  2025-07-28 to 2025-08-24" in result.output
    assert "raised   25-1001" in result.output
    assert "1 raised" in result.output


def test_a_dry_run_says_what_it_would_do_and_writes_nothing(runner, url):
    billed(url)
    result = runner.invoke(
        cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25", "--dry-run"]
    )
    assert result.exit_code == 0
    assert "dry run, nothing written" in result.output
    with unit_of_work(engine_for(url)) as uow:
        assert uow.session.get(InvoiceRow, "25-1001") is None


def test_a_dry_run_does_not_burn_the_number_it_showed(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25", "--dry-run"])
    result = runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    assert "raised   25-1001" in result.output


def test_running_the_month_twice_says_it_skipped(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    assert "skipped  25-1044 (already invoiced)" in result.output
    assert "0 raised" in result.output


def test_a_month_that_is_not_a_month(runner, url):
    result = runner.invoke(cli, ["-d", url, "invoice", "August"])
    assert result.exit_code != 0


def test_writing_a_credit_shows_what_it_did_to_the_balance(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(
        cli,
        [
            "-d",
            url,
            "credit",
            "25-1001",
            "20.00",
            "--reason",
            "did_not_run",
            "--on",
            "2025-08-26",
            "--spots",
            "1",
        ],
    )
    assert result.exit_code == 0
    assert "25-1001 against 25-1001 for did not run" in result.output
    assert "gross      $20.00" in result.output
    assert "owing      $60.00 becomes $40.00" in result.output


def test_a_credit_on_an_agency_invoice_shows_the_commission(runner, url):
    billed(url, advertiser="DEALF", agency="MOP")
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(
        cli,
        ["-d", url, "credit", "25-1001", "20.00", "--reason", "rate_error"],
    )
    assert result.exit_code == 0
    assert "commission $3.00" in result.output
    assert "net        $17.00" in result.output


def test_a_credit_for_some_other_reason_needs_a_note(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(cli, ["-d", url, "credit", "25-1001", "20.00", "--reason", "other"])
    assert result.exit_code == REFUSED
    assert "bad_credit" in result.output
    assert "needs a note" in result.output


def test_a_reason_that_is_not_on_the_list_is_a_misuse(runner, url):
    result = runner.invoke(
        cli, ["-d", url, "credit", "25-1001", "20.00", "--reason", "felt like it"]
    )
    assert result.exit_code != 0


def test_a_credit_bigger_than_what_is_left_is_refused(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(
        cli, ["-d", url, "credit", "25-1001", "100.00", "--reason", "did_not_run"]
    )
    assert result.exit_code == REFUSED
    assert "over_credited" in result.output


def test_nothing_outstanding_says_so(runner, url):
    result = runner.invoke(cli, ["-d", url, "owing"])
    assert result.exit_code == 0
    assert "nothing outstanding" in result.output


def test_what_is_outstanding_is_listed_with_a_total(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    result = runner.invoke(cli, ["-d", url, "owing"])
    assert result.exit_code == 0
    assert "25-1001" in result.output
    assert "$60.00" in result.output
    assert "1 invoices, $60.00 outstanding" in result.output


def test_a_fully_credited_invoice_drops_off_the_list(runner, url):
    billed(url)
    runner.invoke(cli, ["-d", url, "invoice", "2025-08", "--on", "2025-08-25"])
    runner.invoke(cli, ["-d", url, "credit", "25-1001", "60.00", "--reason", "did_not_run"])
    result = runner.invoke(cli, ["-d", url, "owing"])
    assert "nothing outstanding" in result.output
