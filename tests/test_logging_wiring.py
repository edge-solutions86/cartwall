"""The events the office greps for, checked at the places they are written.

These are not tests of the logging module. They are tests that the three jobs
which run unattended say what they did, because an overnight run that fails
silently is the only failure mode that costs a day of air.
"""

from datetime import date, datetime
from io import StringIO

import pytest

from cartwall.broadcast import BroadcastMonth, BroadcastWeek
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logging import configure
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
from cartwall.services.logbuild import build_week
from support import CARD_DATE, MEMORY, MONDAY, a_grid

FIXED = ClassOfTime.FIXED
WEEK = BroadcastWeek(MONDAY)
AUGUST = BroadcastMonth(2025, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def caught():
    stream = StringIO()
    configure("info", stream)
    yield stream
    configure("info", StringIO())


def events(stream) -> list[str]:
    return [text.split(" ")[2] for text in stream.getvalue().splitlines() if text]


@pytest.fixture
def engine(cluster, accounts, cards, programming):
    engine = engine_for(MEMORY)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_grid(programming[call])
        uow.commit()
    return engine


def an_order(per_week=10):
    return Order(
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
                flight=Flight.build(MONDAY, date(2025, 8, 8), "M-F", per_week),
            ),
        ),
    ).confirmed()


def test_a_build_says_what_it_placed_and_what_it_wrote(engine, house, caught):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        build_week(uow, house, WEEK)
        uow.commit()
    assert "build.finished" in events(caught)
    text = caught.getvalue()
    assert "placed=10" in text
    assert "wanted=10" in text
    assert "written=35" in text


def test_a_build_that_cannot_place_everything_says_which_order(engine, house, caught):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=40))
        build_week(uow, house, WEEK)
        uow.commit()
    assert "build.short" in events(caught)
    assert "order=25-1044" in caught.getvalue()


def test_a_build_that_leaves_a_released_day_alone_says_so(engine, house, caught):
    with unit_of_work(engine) as uow:
        logs = LogRepository(uow.session)
        logs.save(DayBoard.build(house.programming["KWLO"], MONDAY))
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
        logs.release("KWLO", MONDAY, SIGNED)
        uow.orders.save(an_order())
        build_week(uow, house, WEEK)
        uow.commit()
    assert "build.skipped" in events(caught)
    assert "state=released" in caught.getvalue()


def test_posting_an_as_run_says_what_aired_and_what_did_not(engine, caught):
    grid = a_grid("KWLO")
    board = DayBoard.build(grid, MONDAY)
    for hour, line in ((7, 1), (9, 1)):
        board.avail(hour, 2).add(
            Placement(
                order="25-1044",
                line=line,
                advertiser="HYVEE",
                length=THIRTY,
                grade=FIXED,
                cart="C1204",
            )
        )
    with unit_of_work(engine) as uow:
        logs = LogRepository(uow.session)
        logs.save(board)
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
        logs.release("KWLO", MONDAY, SIGNED)
        logs.mark_aired("KWLO", MONDAY)
        rows = parse(
            "\n".join(
                [
                    HEADER,
                    "2025-08-04,07:10:00,C1204,a title,30.0,played,25-1044-1",
                    "2025-08-04,09:10:00,C1204,a title,30.0,skip,25-1044-1",
                ]
            )
            + "\n"
        )
        post(uow.session, reconcile(DayLog.build(grid, board), rows))
        uow.commit()
    assert "asrun.posted" in events(caught)
    assert "asrun.missed" in events(caught)
    assert "aired=1" in caught.getvalue()


def test_the_invoice_run_says_what_it_raised(engine, house, caught):
    grid = a_grid("KWLO")
    board = DayBoard.build(grid, MONDAY)
    board.avail(7, 2).add(
        Placement(
            order="25-1044",
            line=1,
            advertiser="HYVEE",
            length=THIRTY,
            grade=FIXED,
            cart="C1204",
        )
    )
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        logs = LogRepository(uow.session)
        logs.save(board)
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
        logs.release("KWLO", MONDAY, SIGNED)
        logs.mark_aired("KWLO", MONDAY)
        rows = parse(HEADER + "\n2025-08-04,07:10:00,C1204,a title,30.0,played,25-1044-1\n")
        post(uow.session, reconcile(DayLog.build(grid, board), rows))
        run_month(uow, AUGUST, date(2025, 8, 25))
        uow.commit()
    written = events(caught)
    assert "invoice.run.started" in written
    assert "invoice.raised" in written
    assert "invoice.run.finished" in written
    assert "invoice=25-1001" in caught.getvalue()
    assert "gross=$60.00" in caught.getvalue()


def test_running_the_invoice_month_twice_says_it_skipped(engine, house, caught):
    with unit_of_work(engine) as uow:
        run_month(uow, AUGUST, date(2025, 8, 25))
        uow.commit()
    assert events(caught) == ["invoice.run.started", "invoice.run.finished"]
    assert "raised=0" in caught.getvalue()
