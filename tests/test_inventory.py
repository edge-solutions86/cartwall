from datetime import date

import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.domain.clock import music_clock, talk_clock
from cartwall.domain.programming import Assignment, ClockGrid
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY, SpotLength
from cartwall.scheduling.inventory import (
    Avail,
    BreakFull,
    DayBoard,
    InventoryError,
    Placement,
)

FIXED = ClassOfTime.FIXED
MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 8)


def a_placement(order="25-1044", line=1, length=THIRTY, **kw):
    return Placement(
        order=order,
        line=line,
        advertiser=kw.pop("advertiser", "HYVEE"),
        length=length,
        grade=kw.pop("grade", FIXED),
        **kw,
    )


@pytest.fixture
def grid():
    grid = ClockGrid("KWLO", date(2025, 1, 1))
    grid.file_clock(music_clock())
    grid.file_clock(talk_clock())
    grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"))
    grid.assign(Assignment.build("farm block", "M-F", 5, 6, "TLK"))
    grid.assign(Assignment.preempt("friday football", "F", 19, 23))
    return grid


@pytest.fixture
def avail(grid):
    return Avail.of("KWLO", MONDAY, 7, music_clock().numbered(2))


def test_a_placement_needs_an_order_and_a_line(avail):
    with pytest.raises(InventoryError, match="names no order"):
        a_placement(order="")
    with pytest.raises(InventoryError, match="not a line number"):
        a_placement(line=0)
    assert a_placement().key == ("25-1044", 1)
    assert str(a_placement()) == "25-1044-1 HYVEE :30"


def test_an_empty_avail_knows_both_of_its_capacities(avail):
    assert avail.units == 6
    assert avail.capacity == Duration.of_seconds(180)
    assert avail.free_units == 6
    assert avail.free_length == Duration.of_seconds(180)
    assert not avail.is_full
    assert avail.starts_at == AirTime.at(7, 10)
    assert str(avail) == "KWLO 2025-08-04 07:10:00 break 2"


def test_six_thirties_fill_the_break_on_both_counts(avail):
    for line in range(1, 7):
        avail.add(a_placement(line=line))
    assert avail.used_units == 6
    assert avail.free_units == 0
    assert avail.free_length == Duration(0)
    assert avail.is_full


def test_three_sixties_fill_it_on_seconds_with_units_to_spare(avail):
    for line in range(1, 4):
        avail.add(a_placement(line=line, length=SIXTY))
    assert avail.used_units == 6
    assert avail.free_length == Duration(0)
    assert avail.is_full


def test_two_sixties_and_a_thirty_still_leave_room_for_one_more(avail):
    avail.add(a_placement(line=1, length=SIXTY))
    avail.add(a_placement(line=2, length=SIXTY))
    avail.add(a_placement(line=3, length=THIRTY))
    assert avail.used_units == 5
    assert avail.free_units == 1
    assert avail.free_length == Duration.of_seconds(30)
    assert avail.accepts(THIRTY)
    assert not avail.accepts(SIXTY)


def test_a_short_spot_costs_a_whole_unit_even_though_it_leaves_seconds(avail):
    for line in range(1, 7):
        avail.add(a_placement(line=line, length=SpotLength(10)))
    assert avail.free_units == 0
    assert avail.free_length == Duration.of_seconds(120)
    assert avail.is_full
    assert not avail.accepts(SpotLength(10))


def test_a_full_break_refuses_and_says_what_is_left(avail):
    avail.add(a_placement(line=1, length=SIXTY))
    avail.add(a_placement(line=2, length=SIXTY))
    avail.add(a_placement(line=3, length=SIXTY))
    with pytest.raises(BreakFull, match="will not take a :30") as caught:
        avail.add(a_placement(line=4))
    assert caught.value.code == "break_full"
    assert caught.value.context["free_units"] == 0
    assert caught.value.context["free_seconds"] == 0.0


def test_a_spot_can_be_pulled_back_out_and_the_room_comes_back(avail):
    avail.add(a_placement(line=1, length=SIXTY))
    avail.add(a_placement(line=2, length=THIRTY))
    assert avail.holds("25-1044", 1)
    dropped = avail.drop("25-1044", 1)
    assert dropped.length == SIXTY
    assert avail.free_units == 5
    assert not avail.holds("25-1044", 1)
    with pytest.raises(InventoryError, match="is not in KWLO hour 7"):
        avail.drop("25-1044", 1)


def test_air_times_fall_out_of_the_lengths_in_front(avail):
    avail.add(a_placement(line=1, length=THIRTY))
    avail.add(a_placement(line=2, length=SIXTY))
    avail.add(a_placement(line=3, length=SpotLength(15)))
    timed = avail.air_times()
    assert [at for _, at in timed] == [
        AirTime.at(7, 10),
        AirTime.at(7, 10, 30),
        AirTime.at(7, 11, 30),
    ]
    assert [placement.line for placement, _ in timed] == [1, 2, 3]


def test_a_board_lays_out_the_selling_breaks_of_every_hour(grid):
    board = DayBoard.build(grid, MONDAY)
    # Twenty three music hours at four sellable breaks, plus the farm hour at four.
    assert len(board) == 23 * 4 + 4
    assert board.hours() == list(range(24))
    assert board.avail(7, 2).units == 6
    assert board.avail(5, 2).units == 8


def test_a_board_skips_the_hours_the_grid_preempts(grid):
    board = DayBoard.build(grid, FRIDAY)
    assert 19 not in board.hours()
    assert 22 not in board.hours()
    assert 23 in board.hours()
    assert 18 in board.hours()


def test_asking_a_board_for_a_break_it_does_not_have(grid):
    board = DayBoard.build(grid, MONDAY)
    with pytest.raises(InventoryError, match="no break 9 in hour 7"):
        board.avail(7, 9)


def test_a_board_counts_its_units_and_reports_how_sold_out_it_is(grid):
    board = DayBoard.build(grid, MONDAY)
    assert board.total_units == 23 * 24 + 30
    assert board.used_units == 0
    assert board.sellout() == 0.0
    board.avail(7, 2).add(a_placement())
    board.avail(7, 3).add(a_placement(line=2, length=SIXTY))
    assert board.used_units == 3
    assert board.free_units == board.total_units - 3
    assert 0 < board.sellout() < 0.01


def test_an_empty_board_is_not_sold_out_by_division():
    empty = DayBoard("KWLO", MONDAY)
    assert empty.sellout() == 0.0
    assert empty.total_units == 0


def test_the_board_lists_the_avails_that_would_take_a_given_length(grid):
    board = DayBoard.build(grid, MONDAY)
    wide_open = board.open_avails(SIXTY)
    assert len(wide_open) == len(board)
    for line in range(1, 4):
        board.avail(7, 2).add(a_placement(line=line, length=SIXTY))
    assert board.avail(7, 2) not in board.open_avails(THIRTY)
    assert board.avail(7, 3) in board.open_avails(THIRTY)


def test_open_avails_come_back_in_air_order(grid):
    board = DayBoard.build(grid, MONDAY)
    order = [(avail.hour, avail.number) for avail in board.open_avails(THIRTY)]
    assert order == sorted(order)
    assert order[0] == (0, 2)


def test_the_board_finds_every_spot_of_one_line_wherever_it_landed(grid):
    board = DayBoard.build(grid, MONDAY)
    board.avail(7, 2).add(a_placement(line=1))
    board.avail(9, 4).add(a_placement(line=1))
    board.avail(9, 4).add(a_placement(line=2))
    assert board.count_for("25-1044", 1) == 2
    assert board.count_for("25-1044", 2) == 1
    assert board.count_for("25-1044", 9) == 0
    assert [avail.hour for avail in board.find("25-1044", 1)] == [7, 9]


def test_everything_on_the_board_comes_back_in_the_order_it_will_air(grid):
    board = DayBoard.build(grid, MONDAY)
    board.avail(9, 4).add(a_placement(line=2))
    board.avail(7, 2).add(a_placement(line=1))
    board.avail(7, 5).add(a_placement(line=3))
    timed = board.placements()
    assert [at.hour for _, at in timed] == [7, 7, 9]
    assert [placement.line for placement, _ in timed] == [1, 3, 2]
