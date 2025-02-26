from datetime import date

import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.logs.day import DayLog, Element, LogError
from cartwall.scheduling.inventory import DayBoard, Placement
from support import FRIDAY, MONDAY

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE


@pytest.fixture
def board(house):
    return DayBoard.build(house.programming["KWLO"], MONDAY)


@pytest.fixture
def grid(house):
    return house.programming["KWLO"]


def put(board, hour, number, code, length=THIRTY, order="25-1044", line=1, cart=""):
    board.avail(hour, number).add(
        Placement(
            order=order,
            line=line,
            advertiser=code,
            length=length,
            grade=FIXED,
            cart=cart,
        )
    )


def test_an_empty_day_still_carries_its_legal_ids(grid, board):
    log = DayLog.build(grid, board)
    assert len(log.legal_ids()) == 24
    assert log.legal_ids()[0].at == AirTime.at(0)
    assert log.legal_ids()[-1].at == AirTime.at(23)
    assert log.spots() == []


def test_an_empty_day_is_all_holes(grid, board):
    log = DayLog.build(grid, board)
    assert log.open_units == 24 * 24
    assert log.sold_units == 0
    assert log.commercial_length == Duration(0)


def test_a_spot_lands_on_the_log_at_the_second_it_airs(grid, board):
    put(board, 7, 2, "HYVEE", cart="C1204")
    log = DayLog.build(grid, board)
    spot = log.spots()[0]
    assert spot.at == AirTime.at(7, 10)
    assert spot.advertiser == "HYVEE"
    assert spot.cart == "C1204"
    assert spot.break_number == 2
    assert spot.hour == 7
    assert spot.grade is FIXED


def test_a_second_spot_in_a_break_starts_where_the_first_one_ends(grid, board):
    put(board, 7, 2, "HYVEE", length=SIXTY, line=1)
    put(board, 7, 2, "SLUMB", length=THIRTY, line=2)
    log = DayLog.build(grid, board)
    assert [spot.at for spot in log.spots()] == [AirTime.at(7, 10), AirTime.at(7, 11)]


def test_the_log_reads_in_air_order_across_the_whole_day(grid, board):
    put(board, 9, 4, "SLUMB", line=1)
    put(board, 7, 2, "HYVEE", line=2)
    put(board, 7, 5, "CASEY", line=3)
    log = DayLog.build(grid, board)
    times = [entry.at for entry in log]
    assert times == sorted(times)
    assert [spot.advertiser for spot in log.spots()] == ["HYVEE", "CASEY", "SLUMB"]


def test_the_holes_in_a_break_show_up_after_what_sold(grid, board):
    put(board, 7, 2, "HYVEE", length=SIXTY)
    lines = DayLog.build(grid, board).in_break(7, 2)
    assert [entry.element for entry in lines] == [
        Element.SPOT,
        Element.UNSOLD,
        Element.UNSOLD,
        Element.UNSOLD,
        Element.UNSOLD,
    ]


def test_the_automation_is_not_handed_the_holes(grid, board):
    put(board, 7, 2, "HYVEE")
    log = DayLog.build(grid, board)
    assert all(entry.element.airs for entry in log.airable())
    assert len(log.airable()) < len(log)
    assert Element.UNSOLD not in {entry.element for entry in log.airable()}


def test_a_full_break_leaves_no_holes(grid, board):
    for line in range(1, 7):
        put(board, 7, 2, "SLUMB", line=line)
    assert [e.element for e in DayLog.build(grid, board).in_break(7, 2)] == [Element.SPOT] * 6


def test_the_network_break_is_on_the_log_and_is_nobodys_to_sell(house):
    grid = house.programming["KCRR"]
    board = DayBoard.build(grid, MONDAY)
    log = DayLog.build(grid, board)
    network = [entry for entry in log.in_hour(7) if entry.element is Element.NETWORK]
    assert len(network) == 1
    assert network[0].at == AirTime.at(7, 30)
    assert network[0].length == Duration.of_seconds(300)


def test_a_preempted_hour_is_simply_not_on_the_log(house):
    grid = house.programming["KCRR"]
    log = DayLog.build(grid, DayBoard.build(grid, FRIDAY))
    assert 20 not in log.hours()
    assert 18 in log.hours()
    assert len(log.legal_ids()) == 20


def test_the_log_totals_what_actually_sold(grid, board):
    put(board, 7, 2, "HYVEE", length=SIXTY, line=1)
    put(board, 7, 3, "SLUMB", length=THIRTY, line=2)
    put(board, 9, 2, "CASEY", length=THIRTY, line=3)
    log = DayLog.build(grid, board)
    assert log.sold_units == 4
    assert log.commercial_length == Duration.of_seconds(120)
    assert log.open_units == 24 * 24 - 4


def test_the_log_finds_one_order_and_one_line_of_it(grid, board):
    put(board, 7, 2, "HYVEE", order="25-1044", line=1)
    put(board, 8, 2, "HYVEE", order="25-1044", line=2)
    put(board, 9, 2, "SLUMB", order="25-2000", line=1)
    log = DayLog.build(grid, board)
    assert len(log.for_order("25-1044")) == 2
    assert len(log.for_order("25-1044", 2)) == 1
    assert log.for_order("25-1044", 2)[0].hour == 8
    assert log.for_order("25-9999") == []


def test_the_log_finds_everything_one_advertiser_has_on_the_day(grid, board):
    put(board, 7, 2, "HYVEE", line=1)
    put(board, 9, 2, "HYVEE", line=2)
    put(board, 9, 3, "SLUMB", line=3)
    log = DayLog.build(grid, board)
    assert [spot.hour for spot in log.for_advertiser("HYVEE")] == [7, 9]
    assert log.for_advertiser("NOBODY") == []


def test_a_log_line_prints_the_way_the_operator_reads_it(grid, board):
    put(board, 7, 2, "HYVEE", cart="C1204")
    log = DayLog.build(grid, board)
    printed = str(log.spots()[0])
    assert "7:10:00a" in printed
    assert "C1204" in printed
    assert "HYVEE" in printed
    legal = str(log.legal_ids()[7])
    assert "legal id" in legal


def test_a_grid_and_a_board_from_two_different_stations_are_refused(house):
    grid = house.programming["KWLO"]
    other = DayBoard.build(house.programming["KCRR"], MONDAY)
    with pytest.raises(LogError, match="was handed KCRR's board"):
        DayLog.build(grid, other)


def test_a_sunday_log_uses_the_sunday_grid(house):
    grid = house.programming["KCNZ"]
    sunday = date(2025, 8, 10)
    monday = DayLog.build(grid, DayBoard.build(grid, MONDAY))
    log = DayLog.build(grid, DayBoard.build(grid, sunday))
    # The farm clock runs Monday to Saturday and has fewer units than talk.
    assert monday.open_units < log.open_units
