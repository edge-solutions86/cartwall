from datetime import date, datetime

import pytest

from cartwall.broadcast import BroadcastWeek
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.scheduling.candidates import Ask
from cartwall.scheduling.inventory import DayBoard
from cartwall.scheduling.lay import Lay
from cartwall.scheduling.pinning import pin
from cartwall.services.logbuild import PinnedBoards, build_week, restore_pins
from support import CARD_DATE, MONDAY

FIXED = ClassOfTime.FIXED
WEEK = BroadcastWeek(MONDAY)
FRIDAY = date(2025, 8, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)


@pytest.fixture
def engine(tmp_path, cluster, accounts, cards, programming):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'build.sqlite3'}")
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_grid(programming[call])
        uow.commit()
    return engine


def a_line(number=1, station="KWLO", daypart="AMD", per_week=10, rate="60.00"):
    return Line(
        number=number,
        station=station,
        daypart=daypart,
        grade=FIXED,
        length=THIRTY,
        rate=Money.parse(rate),
        flight=Flight.build(MONDAY, FRIDAY, "M-F", per_week),
    )


def an_order(number="25-1044", lines=None, advertiser="HYVEE"):
    order = Order(
        number=number,
        advertiser=advertiser,
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=lines if lines is not None else (a_line(),),
    )
    return order.confirmed()


def test_a_week_with_no_orders_still_writes_a_log_a_day_a_station(engine, house):
    with unit_of_work(engine) as uow:
        report = build_week(uow, house, WEEK)
        assert report.orders == []
        assert report.placed == 0
        # Five logged signals, seven days. An empty log is still a log.
        assert len(report.written) == 35


def test_one_order_places_and_the_days_it_touched_are_written(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        report = build_week(uow, house, WEEK)
        uow.commit()
    assert report.placed == 10
    assert report.is_complete
    assert len(report.written) == 35
    assert ("KWLO", MONDAY) in report.written


def test_what_was_written_comes_back_as_a_board(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        build_week(uow, house, WEEK)
        uow.commit()
    with unit_of_work(engine) as uow:
        board = LogRepository(uow.session).board(house.programming["KWLO"], MONDAY)
        assert board.count_for("25-1044", 1) == 2


def test_a_pending_order_is_left_alone(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order().cancelled())
        report = build_week(uow, house, WEEK)
        assert report.orders == []


def test_two_stations_get_two_sets_of_logs(engine, house):
    order = an_order(lines=(a_line(1), a_line(2, station="KCRR", daypart="MID")))
    with unit_of_work(engine) as uow:
        uow.orders.save(order)
        report = build_week(uow, house, WEEK)
        uow.commit()
    assert report.placed == 20
    assert ("KCRR", MONDAY) in report.written


def test_a_rebuild_keeps_the_hand_placed_spots(engine, house):
    with unit_of_work(engine) as uow:
        grid = house.programming["KWLO"]
        lay = Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", MONDAY))
        book = uow.accounts.book()
        ask = Ask(
            order="25-0900", line=1, advertiser=book.advertiser("SLUMB"),
            daypart="AMD", length=THIRTY, grade=FIXED, cart="C9001", agency="CRS",
        )
        pin(lay, book, ask, 7, 5)
        LogRepository(uow.session).save(lay.board)
        uow.orders.save(an_order())
        uow.commit()

    with unit_of_work(engine) as uow:
        build_week(uow, house, WEEK)
        uow.commit()

    with unit_of_work(engine) as uow:
        board = LogRepository(uow.session).board(house.programming["KWLO"], MONDAY)
        assert board.count_for("25-0900", 1) == 1
        assert board.avail(7, 5).placements[0].fixed


def test_a_rebuild_throws_away_what_the_builder_itself_placed(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        build_week(uow, house, WEEK)
        uow.commit()
    with unit_of_work(engine) as uow:
        uow.orders.delete("25-1044")
        report = build_week(uow, house, WEEK)
        uow.commit()
    assert report.placed == 0
    with unit_of_work(engine) as uow:
        board = LogRepository(uow.session).board(house.programming["KWLO"], MONDAY)
        assert board.used_units == 0


def test_a_second_order_added_later_places_beside_the_first(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        build_week(uow, house, WEEK)
        uow.commit()
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order("25-2000", lines=(a_line(1, daypart="MID"),)))
        report = build_week(uow, house, WEEK)
        uow.commit()
    # Both orders are placed from scratch, so both are on the rebuilt board once.
    assert report.placed == 20
    with unit_of_work(engine) as uow:
        board = LogRepository(uow.session).board(house.programming["KWLO"], MONDAY)
        assert board.count_for("25-1044", 1) == 2
        assert board.count_for("25-2000", 1) == 2


def test_a_day_that_has_gone_out_is_not_touched(engine, house):
    with unit_of_work(engine) as uow:
        logs = LogRepository(uow.session)
        logs.save(DayBoard.build(house.programming["KWLO"], MONDAY))
        logs.approve("KWLO", MONDAY, "T. Ronan", SIGNED)
        logs.release("KWLO", MONDAY, SIGNED)
        uow.orders.save(an_order())
        report = build_week(uow, house, WEEK)
        uow.commit()
    assert ("KWLO", MONDAY, "released") in report.skipped
    assert (("KWLO", MONDAY)) not in report.written
    with unit_of_work(engine) as uow:
        board = LogRepository(uow.session).board(house.programming["KWLO"], MONDAY)
        assert board.used_units == 0


def test_the_report_reads_as_a_sentence(engine, house):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        report = build_week(uow, house, WEEK)
        uow.commit()
    printed = str(report)
    assert "10 of 10 placed" in printed
    assert "35 logs written" in printed


def test_a_shortfall_is_reported_order_by_order(engine, house):
    heavy = an_order(lines=(a_line(1, per_week=40),))
    with unit_of_work(engine) as uow:
        uow.orders.save(heavy)
        report = build_week(uow, house, WEEK)
        uow.commit()
    assert not report.is_complete
    assert [result.order.number for result in report.shortfalls()] == ["25-1044"]


def test_restoring_pins_copies_only_the_hand_placed_spots(house, accounts):
    grid = house.programming["KWLO"]
    lay = Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", MONDAY))
    ask = Ask(
        order="25-0900", line=1, advertiser=accounts.advertiser("SLUMB"),
        daypart="AMD", length=THIRTY, grade=FIXED, cart="C9001",
    )
    pin(lay, accounts, ask, 7, 5)
    from cartwall.scheduling.pinning import put_spot

    put_spot(lay, lay.board.avail(9, 2), ask)
    fresh = DayBoard.build(grid, MONDAY)
    kept = restore_pins(fresh, lay.board)
    assert len(kept) == 1
    assert fresh.used_units == 1
    assert fresh.avail(7, 5).placements[0].fixed


def test_the_board_cache_starts_from_what_is_stored(engine, house):
    with unit_of_work(engine) as uow:
        board = DayBoard.build(house.programming["KWLO"], MONDAY)
        LogRepository(uow.session).save(board)
        uow.commit()
    with unit_of_work(engine) as uow:
        boards = PinnedBoards(house, LogRepository(uow.session))
        assert boards.board("KWLO", MONDAY).used_units == 0
        assert boards.built() == [("KWLO", MONDAY)]
