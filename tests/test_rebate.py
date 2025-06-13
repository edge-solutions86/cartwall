from datetime import date, datetime

import pytest

from cartwall.broadcast import BroadcastMonth
from cartwall.domain.account import Advertiser
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.political import Charge, ChargeBook, Election, Race
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.day import DayLog
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import post
from cartwall.scheduling.inventory import DayBoard, Placement
from cartwall.services.invoicing import run_month
from cartwall.services.rebate import RebateError, charge_book, owed_for
from support import CARD_DATE, a_grid

IMMEDIATE = ClassOfTime.IMMEDIATE
FIXED = ClassOfTime.FIXED

# A special election on 2025-08-26 puts its sixty day window over August.
ELECTION = date(2025, 8, 26)
RACE = Race("Iowa House 63", Election.SPECIAL, ELECTION)
AUGUST = BroadcastMonth(2025, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)
HEADER = "date,time,cart,title,length,status,reference"

# 2025-08-04 is inside the window, which opened on 06-27.
AIR_DAY = date(2025, 8, 4)


@pytest.fixture
def engine(tmp_path, cluster, accounts, cards, programming):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'rebate.sqlite3'}")
    create_all(engine)
    accounts.add_advertiser(Advertiser.build("MERRICK", "Merrick for Iowa", "POLITICAL"))
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.cards.file(cards.in_force("KWLO", CARD_DATE))
        uow.stations.save_grid(programming["KWLO"])
        uow.commit()
    return engine


def an_order(number, advertiser, rate, grade=IMMEDIATE, per_week=2):
    return Order(
        number=number,
        advertiser=advertiser,
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=(
            Line(
                number=1,
                station="KWLO",
                daypart="AMD",
                grade=grade,
                length=THIRTY,
                rate=Money.parse(rate),
                flight=Flight.build(AIR_DAY, date(2025, 8, 8), "M-F", per_week),
            ),
        ),
    ).confirmed()


def bill(engine, placements, asrun):
    """Put spots on a day, air them, post the as-run, raise the invoices."""
    grid = a_grid("KWLO")
    board = DayBoard.build(grid, AIR_DAY)
    for hour, number, order, cart in placements:
        board.avail(hour, number).add(
            Placement(
                order=order,
                line=1,
                advertiser="HYVEE",
                length=THIRTY,
                grade=IMMEDIATE,
                cart=cart,
            )
        )
    with unit_of_work(engine) as uow:
        logs = LogRepository(uow.session)
        logs.save(board)
        logs.approve("KWLO", AIR_DAY, "T. Ronan", SIGNED)
        logs.release("KWLO", AIR_DAY, SIGNED)
        logs.mark_aired("KWLO", AIR_DAY)
        post(uow.session, reconcile(DayLog.build(grid, board), parse(asrun)))
        run_month(uow, AUGUST, date(2025, 8, 25))
        uow.commit()


def ran(at, cart, reference):
    return f"2025-08-04,{at},{cart},a title,30.0,played,{reference}"


def test_the_window_of_a_special_election_reaches_back_sixty_days():
    assert RACE.window_opens == date(2025, 6, 27)
    assert RACE.in_window(AIR_DAY)


def test_a_run_with_no_political_accounts_is_refused(engine):
    with (
        unit_of_work(engine) as uow,
        pytest.raises(RebateError, match="at least one political account"),
    ):
        owed_for(uow, RACE, [])


def test_a_candidate_charged_the_lowest_is_owed_nothing(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "18.00"))
        uow.orders.save(an_order("25-2000", "HYVEE", "36.00"))
        uow.commit()
    bill(
        engine,
        [(7, 2, "25-1044", "C1204"), (9, 2, "25-2000", "C1205")],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"])
    assert report.checked == 1
    assert report.is_clean
    assert report.total == Money(0)


def test_a_candidate_charged_above_the_lowest_is_owed_the_difference(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00"))
        uow.orders.save(an_order("25-2000", "HYVEE", "18.00"))
        uow.commit()
    bill(
        engine,
        [(7, 2, "25-1044", "C1204"), (9, 2, "25-2000", "C1205")],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"])
    assert not report.is_clean
    assert len(report.owed) == 1
    owed = report.owed[0]
    assert owed.charged == Money.dollars(36)
    assert owed.lowest == Money.dollars(18)
    assert owed.each == Money.dollars(18)
    assert owed.spots == 1
    assert owed.amount == Money.dollars(18)
    assert report.total == Money.dollars(18)


def test_the_amount_owed_multiplies_by_the_spots_that_ran(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00"))
        uow.orders.save(an_order("25-2000", "HYVEE", "18.00"))
        uow.commit()
    bill(
        engine,
        [
            (7, 2, "25-1044", "C1204"),
            (9, 2, "25-1044", "C1204"),
            (11, 2, "25-2000", "C1205"),
        ],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1204", "25-1044-1"),
                ran("11:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"])
    assert report.owed[0].spots == 2
    assert report.total == Money.dollars(36)


def test_a_cheaper_charge_in_another_class_does_not_lower_the_candidates_rate(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00", grade=IMMEDIATE))
        uow.orders.save(an_order("25-2000", "HYVEE", "18.00", grade=FIXED))
        uow.commit()
    bill(
        engine,
        [(7, 2, "25-1044", "C1204"), (9, 2, "25-2000", "C1205")],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"])
    assert report.is_clean


def test_the_charge_book_is_built_from_what_was_invoiced(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00"))
        uow.orders.save(an_order("25-2000", "HYVEE", "18.00"))
        uow.commit()
    bill(
        engine,
        [(7, 2, "25-1044", "C1204"), (9, 2, "25-2000", "C1205")],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        book = charge_book(uow, RACE.window_opens, RACE.election_day)
    assert len(book) == 2
    assert book.lowest(
        "KWLO", "AMD", IMMEDIATE, THIRTY, RACE.window_opens, RACE.election_day
    ) == Money.dollars(18)


def test_an_order_that_was_never_invoiced_is_not_in_the_book(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-3000", "SLUMB", "1.00"))
        uow.commit()
    with unit_of_work(engine) as uow:
        book = charge_book(uow, RACE.window_opens, RACE.election_day)
    assert len(book) == 0


def test_a_book_can_be_handed_in_so_a_what_if_can_be_run(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00"))
        uow.commit()
    bill(
        engine,
        [(7, 2, "25-1044", "C1204")],
        HEADER + "\n" + ran("07:10:00", "C1204", "25-1044-1") + "\n",
    )
    proposed = ChargeBook(
        [
            Charge(
                station="KWLO",
                daypart="AMD",
                grade=IMMEDIATE,
                length=THIRTY,
                rate=Money.dollars(12),
                day=AIR_DAY,
                advertiser="SLUMB",
            )
        ]
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"], book=proposed)
    assert report.total == Money.dollars(24)


def test_the_report_totals_by_account_worst_first(engine):
    with unit_of_work(engine) as uow:
        uow.accounts.save_book(_with_second_candidate(uow))
        uow.orders.save(an_order("25-1044", "MERRICK", "36.00"))
        uow.orders.save(an_order("25-1500", "HOLLIS", "30.00"))
        uow.orders.save(an_order("25-2000", "HYVEE", "18.00"))
        uow.commit()
    bill(
        engine,
        [
            (7, 2, "25-1044", "C1204"),
            (9, 2, "25-1500", "C1206"),
            (11, 2, "25-2000", "C1205"),
        ],
        "\n".join(
            [
                HEADER,
                ran("07:10:00", "C1204", "25-1044-1"),
                ran("09:10:00", "C1206", "25-1500-1"),
                ran("11:10:00", "C1205", "25-2000-1"),
            ]
        )
        + "\n",
    )
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK", "HOLLIS"])
    tally = report.by_advertiser()
    assert list(tally) == ["MERRICK", "HOLLIS"]
    assert tally["MERRICK"] == Money.dollars(18)
    assert tally["HOLLIS"] == Money.dollars(12)
    assert len(report.for_advertiser("MERRICK")) == 1
    assert report.for_advertiser("NOBODY") == []


def test_the_report_reads_as_a_sentence(engine):
    with unit_of_work(engine) as uow:
        report = owed_for(uow, RACE, ["MERRICK"])
    assert str(report) == (
        "Iowa House 63 special 08/26/2025: 0 line(s) checked, 0 owed, $0.00 in total"
    )


def _with_second_candidate(uow):
    book = uow.accounts.book()
    book.add_advertiser(Advertiser.build("HOLLIS", "Hollis for Iowa", "POLITICAL"))
    return book
