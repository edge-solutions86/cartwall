from datetime import date

import pytest

from cartwall.broadcast import BroadcastWeek
from cartwall.domain.account import Advertiser, Credit
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.rotation import Rotation
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money
from cartwall.scheduling.run import (
    Boards,
    RunError,
    placement_order,
    schedule_all,
    schedule_order,
)
from support import MONDAY

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE
IMMEDIATE = ClassOfTime.IMMEDIATE

WEEK = BroadcastWeek(MONDAY)
FRIDAY = date(2025, 8, 8)


def a_flight(days="M-F", per_week=10, first=MONDAY, last=FRIDAY):
    return Flight.build(first, last, days, per_week)


def a_line(number=1, station="KWLO", daypart="AMD", grade=FIXED, rate="60.00", **kw):
    return Line(
        number=number,
        station=station,
        daypart=daypart,
        grade=grade,
        length=kw.pop("length", THIRTY),
        rate=Money.parse(rate),
        flight=kw.pop("flight", a_flight()),
        carts=kw.pop("carts", ()),
    )


def an_order(lines=None, **kw):
    order = Order(
        number=kw.pop("number", "25-1044"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=lines if lines is not None else (a_line(),),
        agency=kw.pop("agency", None),
        campaign=kw.pop("campaign", ""),
    )
    return order.confirmed() if kw.pop("confirm", True) else order


@pytest.fixture
def boards(house):
    return Boards(house)


def test_the_board_cache_builds_each_station_day_once(boards):
    first = boards.board("KWLO", MONDAY)
    again = boards.board("KWLO", MONDAY)
    assert first is again
    boards.board("KCRR", MONDAY)
    boards.board("KWLO", FRIDAY)
    assert len(boards) == 3
    assert boards.stations() == ["KCRR", "KWLO"]
    assert boards.days() == [MONDAY, FRIDAY]


def test_the_lay_for_a_station_day_carries_the_matching_rules(boards):
    lay = boards.lay("KWLO", MONDAY)
    assert lay.book.call == "KWLO"
    assert lay.board.day == MONDAY
    assert boards.lay("KWLO", MONDAY) is lay


def test_hardest_to_place_goes_first():
    lines = (
        a_line(1, grade=IMMEDIATE, rate="12.00"),
        a_line(2, grade=FIXED, rate="60.00"),
        a_line(3, grade=NOTICE, rate="48.00"),
        a_line(4, grade=FIXED, rate="32.00"),
    )
    assert [line.number for line in placement_order(lines)] == [2, 4, 3, 1]


def test_a_pending_order_is_not_scheduled(boards, accounts):
    order = an_order(confirm=False)
    with pytest.raises(RunError, match="is pending and cannot be scheduled"):
        schedule_order(boards, accounts, order, WEEK)


def test_a_cancelled_order_is_not_scheduled(boards, accounts):
    order = an_order().cancelled()
    with pytest.raises(RunError, match="is cancelled"):
        schedule_order(boards, accounts, order, WEEK)


def test_an_advertiser_on_credit_hold_is_not_scheduled(boards, accounts):
    accounts.add_advertiser(Advertiser.build("SLOW", "Slow Pay", credit=Credit.HOLD))
    with pytest.raises(RunError, match="SLOW is on credit hold") as caught:
        schedule_order(boards, accounts, an_order(advertiser="SLOW"), WEEK)
    assert caught.value.context["order"] == "25-1044"


def test_a_one_line_order_places_its_week(boards, accounts):
    result = schedule_order(boards, accounts, an_order(), WEEK)
    assert result.wanted == 10
    assert result.placed == 10
    assert result.is_complete
    assert result.shortfalls() == []
    assert str(result).startswith("25-1044 10 of 10")


def test_two_lines_of_one_order_share_a_board_and_stay_apart(boards, accounts):
    order = an_order(lines=(a_line(1), a_line(2, daypart="MID", rate="32.00")))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.placed == 20
    assert result.is_complete
    board = boards.board("KWLO", MONDAY)
    hours = [at.hour for _, at in board.placements()]
    assert set(hours) <= {6, 7, 8, 9, 10, 11, 12, 13, 14}


def test_lines_on_two_stations_use_two_boards(boards, accounts):
    order = an_order(lines=(a_line(1), a_line(2, station="KCRR", daypart="MID")))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.placed == 20
    assert boards.stations() == ["KCRR", "KWLO"]


def test_a_line_bought_on_the_translator_is_scheduled_on_the_parent(boards, accounts):
    order = an_order(lines=(a_line(1, station="KWLO-LP"),))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.placed == 10
    assert boards.stations() == ["KWLO"]


def test_the_result_comes_back_in_line_number_order(boards, accounts):
    order = an_order(
        lines=(
            a_line(1, grade=IMMEDIATE, rate="12.00", daypart="ROS"),
            a_line(2, grade=FIXED, rate="60.00"),
            a_line(3, grade=NOTICE, rate="48.00", daypart="MID"),
        )
    )
    result = schedule_order(boards, accounts, order, WEEK)
    assert [entry.line.number for entry in result.lines] == [1, 2, 3]
    assert all(entry.is_complete for entry in result.lines)


def test_a_line_that_does_not_run_this_week_wants_nothing(boards, accounts):
    away = a_flight(first=date(2025, 9, 1), last=date(2025, 9, 5))
    order = an_order(lines=(a_line(1, flight=away),))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.wanted == 0
    assert result.placed == 0
    assert not result.is_complete
    assert "no air days" in str(result.lines[0].result.missed[0])


def test_a_stub_week_asks_for_fewer_and_gets_them(boards, accounts):
    thursday = date(2025, 8, 7)
    stub = a_flight(first=thursday, last=date(2025, 8, 22), per_week=10)
    order = an_order(lines=(a_line(1, flight=stub),))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.wanted == 4
    assert result.placed == 4


def test_several_carts_on_a_line_rotate_without_being_asked(boards, accounts):
    order = an_order(lines=(a_line(1, carts=("C1204", "C1205")),))
    result = schedule_order(boards, accounts, order, WEEK)
    carts = [spot.cart for spot in result.lines[0].result.landed]
    assert sorted(carts).count("C1204") == 5
    assert sorted(carts).count("C1205") == 5


def test_a_named_rotation_beats_the_carts_on_the_line(boards, accounts):
    order = an_order(lines=(a_line(1, carts=("C1204", "C1205")),))
    weighted = Rotation.weighted([("C1204", 4), ("C1205", 1)])
    result = schedule_order(boards, accounts, order, WEEK, {1: weighted})
    carts = [spot.cart for spot in result.lines[0].result.landed]
    assert carts.count("C1204") == 8
    assert carts.count("C1205") == 2


def test_one_cart_on_a_line_goes_on_every_spot(boards, accounts):
    order = an_order(lines=(a_line(1, carts=("C1204",)),))
    result = schedule_order(boards, accounts, order, WEEK)
    assert {spot.cart for spot in result.lines[0].result.landed} == {"C1204"}


def test_a_shortfall_is_reported_line_by_line(boards, accounts):
    heavy = a_line(1, flight=a_flight(per_week=40))
    result = schedule_order(boards, accounts, an_order(lines=(heavy,)), WEEK)
    assert result.wanted == 40
    assert result.placed < 40
    assert not result.is_complete
    assert [entry.line.number for entry in result.shortfalls()] == [1]
    assert str(result.lines[0]).startswith("line 1")


def test_a_whole_week_of_orders_runs_dearest_first(boards, accounts):
    small = an_order(number="25-1000", lines=(a_line(1, rate="12.00", daypart="ROS"),))
    big = an_order(
        number="25-2000",
        advertiser="DEALF",
        agency="MOP",
        lines=(a_line(1, rate="60.00"),),
    )
    results = schedule_all(boards, accounts, [small, big], WEEK)
    assert [result.order.number for result in results] == ["25-2000", "25-1000"]
    assert all(result.is_complete for result in results)


def test_a_week_run_leaves_unconfirmed_orders_alone(boards, accounts):
    confirmed = an_order(number="25-1000")
    pending = an_order(number="25-2000", confirm=False)
    results = schedule_all(boards, accounts, [confirmed, pending], WEEK)
    assert [result.order.number for result in results] == ["25-1000"]


def test_a_sixty_second_line_places_the_same_count_as_a_thirty(boards, accounts):
    order = an_order(lines=(a_line(1, length=SIXTY),))
    result = schedule_order(boards, accounts, order, WEEK)
    assert result.placed == 10
