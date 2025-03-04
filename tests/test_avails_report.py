from datetime import timedelta

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.money import Money
from cartwall.reports.avails import (
    ReportError,
    Row,
    by_day,
    by_daypart,
    by_hour,
    thinnest_hours,
    week_open_units,
    week_value,
)
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY

FIXED = ClassOfTime.FIXED


@pytest.fixture
def board(house):
    return DayBoard.build(house.programming["KWLO"], MONDAY)


@pytest.fixture
def book(house):
    return house.book("KWLO", MONDAY)


def put(board, hour, number, count=1, code="HYVEE"):
    for line in range(count):
        board.avail(hour, number).add(
            Placement(
                order="25-1044",
                line=line + 1,
                advertiser=code,
                length=THIRTY,
                grade=FIXED,
            )
        )


def test_a_row_works_out_its_own_hole_and_its_own_sellout():
    row = Row("AMD", 96, 24)
    assert row.open == 72
    assert row.sellout == 0.25
    assert "AMD" in str(row)
    assert "25.0%" in str(row)


def test_an_empty_row_does_not_divide_by_zero():
    assert Row("XXX", 0, 0).sellout == 0.0


def test_an_untouched_day_is_open_everywhere(board, book):
    rows = {row.label: row for row in by_daypart(board, book)}
    assert rows["AMD"].units == 96
    assert rows["AMD"].sold == 0
    assert rows["AMD"].sellout == 0.0
    assert rows["ROS"].units == 24 * 24


def test_the_dayparts_come_back_narrowest_first(board, book):
    labels = [row.label for row in by_daypart(board, book)]
    assert labels.index("AMD") < labels.index("AAA") < labels.index("ROS")


def test_the_rows_overlap_and_are_not_meant_to_add_up(board, book):
    rows = {row.label: row for row in by_daypart(board, book)}
    weekday_parts = rows["AMD"].units + rows["MID"].units + rows["PMD"].units
    assert weekday_parts < rows["ROS"].units
    assert rows["AAA"].units > rows["AMD"].units
    assert sum(row.units for row in by_daypart(board, book)) > rows["ROS"].units


def test_a_sold_unit_shows_up_in_every_daypart_that_covers_it(board, book):
    put(board, 7, 2)
    rows = {row.label: row for row in by_daypart(board, book)}
    assert rows["AMD"].sold == 1
    assert rows["AAA"].sold == 1
    assert rows["ROS"].sold == 1
    assert rows["MID"].sold == 0


def test_the_hole_is_priced_at_the_cheapest_class_the_card_quotes(board, book):
    rows = {row.label: row for row in by_daypart(board, book)}
    # KWLO morning drive is quoted at 36 immediately preemptible, 96 units open.
    assert rows["AMD"].value == Money.dollars(36 * 96)
    # Saturday is only quoted at notice, so that is what prices it.
    assert rows["ROS"].value == Money.dollars(12 * 24 * 24)


def test_selling_a_unit_takes_its_value_out_of_the_hole(board, book):
    before = {row.label: row for row in by_daypart(board, book)}["AMD"].value
    put(board, 7, 2)
    after = {row.label: row for row in by_daypart(board, book)}["AMD"].value
    assert before - after == Money.dollars(36)


def test_a_saturday_report_uses_the_saturday_dayparts(house):
    saturday = MONDAY + timedelta(days=5)
    board = DayBoard.build(house.programming["KWLO"], saturday)
    rows = {row.label: row for row in by_daypart(board, house.book("KWLO", saturday))}
    assert "AMD" not in rows
    assert rows["SAT"].units == 18 * 24
    assert rows["OVN"].units == 6 * 24


def test_a_board_and_a_card_from_two_stations_are_refused(board, house):
    with pytest.raises(ReportError, match="was handed KWLO's board"):
        by_daypart(board, house.book("KCRR", MONDAY))


def test_the_hour_report_runs_in_air_order(board):
    rows = by_hour(board)
    assert len(rows) == 24
    assert rows[0].label == "12:00a"
    assert rows[7].label == "7:00a"
    assert rows[13].label == "1:00p"
    assert all(row.units == 24 for row in rows)


def test_the_hour_report_counts_what_sold_in_that_hour(board):
    put(board, 7, 2, count=6)
    put(board, 7, 3, count=3)
    rows = {row.label: row for row in by_hour(board)}
    assert rows["7:00a"].sold == 9
    assert rows["7:00a"].open == 15
    assert rows["8:00a"].sold == 0


def test_the_thinnest_hours_are_where_a_schedule_will_jam(board):
    put(board, 7, 2, count=6)
    put(board, 7, 3, count=6)
    put(board, 7, 4, count=6)
    put(board, 15, 2, count=6)
    thin = thinnest_hours(board, 2)
    assert thin[0].label == "7:00a"
    assert thin[0].open == 6
    assert thin[1].label == "3:00p"


def test_a_preempted_hour_is_not_on_the_hour_report(house):
    friday = MONDAY + timedelta(days=4)
    board = DayBoard.build(house.programming["KCRR"], friday)
    labels = [row.label for row in by_hour(board)]
    assert "7:00p" not in labels
    assert "6:00p" in labels
    assert len(labels) == 20


def test_the_day_report_runs_a_week_of_one_station(house):
    grid = house.programming["KWLO"]
    boards = [DayBoard.build(grid, MONDAY + timedelta(days=n)) for n in range(7)]
    put(boards[4], 7, 2, count=6)
    rows = by_day(boards)
    assert [row.label for row in rows][0] == "Mon 08/04"
    assert rows[4].sold == 6
    assert rows[0].sold == 0
    assert len(rows) == 7


def test_the_day_report_refuses_a_mixed_bag_of_stations(house):
    boards = [
        DayBoard.build(house.programming["KWLO"], MONDAY),
        DayBoard.build(house.programming["KCRR"], MONDAY),
    ]
    with pytest.raises(ReportError, match="one station, not 2"):
        by_day(boards)


def test_the_week_totals_what_is_open_and_what_it_would_fetch(house):
    grid = house.programming["KWLO"]
    days = [MONDAY + timedelta(days=n) for n in range(5)]
    boards = [DayBoard.build(grid, day) for day in days]
    books = {day: house.book("KWLO", day) for day in days}
    put(boards[0], 7, 2, count=6)
    assert week_open_units(boards) == 5 * 24 * 24 - 6
    assert week_value(boards, books, "AMD") == Money.dollars(36 * (5 * 96 - 6))


def test_a_week_value_with_a_day_nobody_gave_rules_for(house):
    grid = house.programming["KWLO"]
    boards = [DayBoard.build(grid, MONDAY)]
    with pytest.raises(ReportError, match="no rules were given for 2025-08-04"):
        week_value(boards, {}, "AMD")
