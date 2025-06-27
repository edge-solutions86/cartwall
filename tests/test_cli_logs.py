from datetime import date, datetime

import pytest
from click.testing import CliRunner

from cartwall.airtime import Duration
from cartwall.cli.main import REFUSED, cli
from cartwall.domain.daypart import standard_grid
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY, Cut
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.house import grid_for
from support import CARD_DATE, MONDAY

FIXED = ClassOfTime.FIXED
FRIDAY = date(2025, 8, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def url(tmp_path, cluster, accounts, cards, programming):
    url = f"sqlite+pysqlite:///{tmp_path / 'clilogs.sqlite3'}"
    engine = engine_for(url)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_dayparts(call, standard_grid())
            uow.stations.save_grid(programming[call])
        # Production dubs the copy before traffic can name a cart on a line.
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


def an_order(url, carts=("C1204",)):
    engine = engine_for(url)
    with unit_of_work(engine) as uow:
        uow.orders.save(
            Order(
                number="25-1044",
                advertiser="HYVEE",
                written=date(2025, 7, 21),
                salesperson="DHOLM",
                lines=(
                    Line(
                        number=1,
                        station="KWLO",
                        daypart="AMD",
                        grade=FIXED,
                        length=THIRTY,
                        rate=Money.dollars(60),
                        flight=Flight.build(MONDAY, FRIDAY, "M-F", 5),
                        carts=tuple(carts),
                    ),
                ),
            ).confirmed()
        )
        uow.commit()


def test_building_a_week_says_what_it_did(runner, url):
    an_order(url)
    result = runner.invoke(cli, ["-d", url, "build", "2025-08-06"])
    assert result.exit_code == 0
    assert "week of 2025-08-04" in result.output
    assert "placed   5 of 5" in result.output
    assert "written  35 logs" in result.output


def test_building_names_the_orders_it_could_not_fill(runner, url):
    engine = engine_for(url)
    with unit_of_work(engine) as uow:
        uow.orders.save(
            Order(
                number="25-2000",
                advertiser="HYVEE",
                written=date(2025, 7, 21),
                salesperson="DHOLM",
                lines=(
                    Line(
                        number=1,
                        station="KWLO",
                        daypart="AMD",
                        grade=FIXED,
                        length=THIRTY,
                        rate=Money.dollars(60),
                        flight=Flight.build(MONDAY, FRIDAY, "M-F", 40),
                    ),
                ),
            ).confirmed()
        )
        uow.commit()
    result = runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    assert result.exit_code == 0
    assert "short    25-2000" in result.output


def test_a_day_that_is_not_a_date_is_a_misuse(runner, url):
    result = runner.invoke(cli, ["-d", url, "build", "the fourth"])
    assert result.exit_code != 0
    assert "YYYY-MM-DD" in result.output


def test_exporting_writes_the_automations_format_to_the_screen(runner, url):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "build-export", "KWLO", "2025-08-04"])
    assert result.exit_code == 0
    lines = result.output.splitlines()
    assert lines[0].startswith("00 00:00:00 LID")
    assert any(" SPT " in line and "HYVEE" in line for line in lines)


def test_exporting_writes_a_file_when_told_where(runner, url, tmp_path):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    into = tmp_path / "watch"
    into.mkdir()
    result = runner.invoke(
        cli, ["-d", url, "build-export", "KWLO", "2025-08-04", "--into", str(into)]
    )
    assert result.exit_code == 0
    assert "lines written" in result.output
    written = into / "KWLO250804.LOG"
    assert written.exists()
    assert "\r\n" not in written.read_text()


def test_exporting_a_translator_writes_the_parents_log(runner, url, tmp_path):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    into = tmp_path / "watch"
    into.mkdir()
    runner.invoke(cli, ["-d", url, "build-export", "KWLO-LP", "2025-08-04", "--into", str(into)])
    assert (into / "KWLO250804.LOG").exists()


def test_checking_a_log_with_copy_says_it_is_ready(runner, url):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "check", "KWLO", "2025-08-04"])
    assert result.exit_code == 0
    assert "ready to sign" in result.output
    assert "warning  unsold" in result.output


def test_checking_a_log_with_no_copy_refuses_and_says_why(runner, url):
    an_order(url, carts=())
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "check", "KWLO", "2025-08-04"])
    assert result.exit_code == REFUSED
    assert "problem  missing_copy" in result.output
    assert "is not ready" in result.output


def test_signing_a_log_records_who(runner, url):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "approve", "KWLO", "2025-08-04", "--who", "T. Ronan"])
    assert result.exit_code == 0
    assert "approved by T. Ronan" in result.output


def test_signing_a_log_with_a_problem_is_refused(runner, url):
    an_order(url, carts=())
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "approve", "KWLO", "2025-08-04", "--who", "T. Ronan"])
    assert result.exit_code == REFUSED
    assert "problem(s)" in result.output


def test_signing_needs_a_name(runner, url):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    result = runner.invoke(cli, ["-d", url, "approve", "KWLO", "2025-08-04"])
    assert result.exit_code != 0


def test_releasing_a_signed_log(runner, url):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    runner.invoke(cli, ["-d", url, "approve", "KWLO", "2025-08-04", "--who", "T. Ronan"])
    result = runner.invoke(cli, ["-d", url, "release", "KWLO", "2025-08-04"])
    assert result.exit_code == 0
    assert "released at revision 0" in result.output


def test_posting_an_as_run_says_what_aired(runner, url, tmp_path):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    runner.invoke(cli, ["-d", url, "approve", "KWLO", "2025-08-04", "--who", "T. Ronan"])
    runner.invoke(cli, ["-d", url, "release", "KWLO", "2025-08-04"])
    engine = engine_for(url)
    with unit_of_work(engine) as uow:
        LogRepository(uow.session).mark_aired("KWLO", MONDAY)
        grid = grid_for(uow, "KWLO", MONDAY)
        board = LogRepository(uow.session).board(grid, MONDAY)
        uow.commit()
    spots = [(placement, at) for placement, at in board.placements()]
    lines = [HEADER] + [
        f"2025-08-04,{at},{placement.cart},a title,30.0,played,{placement.order}-{placement.line}"
        for placement, at in spots
    ]
    path = tmp_path / "KWLO250804.ASR"
    path.write_text("\n".join(lines) + "\n")

    result = runner.invoke(cli, ["-d", url, "post-asrun", "KWLO", "2025-08-04", str(path)])
    assert result.exit_code == 0
    assert "aired    1" in result.output
    assert "missed   0" in result.output


def test_posting_against_a_day_that_has_not_aired_is_refused(runner, url, tmp_path):
    an_order(url)
    runner.invoke(cli, ["-d", url, "build", "2025-08-04"])
    path = tmp_path / "empty.asr"
    path.write_text(HEADER + "\n")
    result = runner.invoke(cli, ["-d", url, "post-asrun", "KWLO", "2025-08-04", str(path)])
    assert result.exit_code == REFUSED
    assert "not_aired" in result.output


def test_posting_a_file_that_is_not_there(runner, url):
    result = runner.invoke(cli, ["-d", url, "post-asrun", "KWLO", "2025-08-04", "nowhere.asr"])
    assert result.exit_code != 0
