import pytest

from cartwall.airtime import AirTime
from cartwall.domain.daypart import (
    CELLS_IN_WEEK,
    Daypart,
    DaypartError,
    DaypartGrid,
    Weekday,
    parse_days,
    show_days,
    standard_grid,
)

MON = Weekday.MON
SAT = Weekday.SAT
SUN = Weekday.SUN


def test_the_shapes_the_sales_side_writes_days_in():
    assert parse_days("M-F") == frozenset({MON, Weekday.TUE, Weekday.WED, Weekday.THU, Weekday.FRI})
    assert parse_days("Sa") == frozenset({SAT})
    assert parse_days("Sa,Su") == frozenset({SAT, SUN})
    assert parse_days("M,W,F") == frozenset({MON, Weekday.WED, Weekday.FRI})
    assert len(parse_days("M-Su")) == 7
    assert parse_days("m-f") == parse_days("M-F")
    assert parse_days("M - F") == parse_days("M-F")


def test_days_that_are_not_days():
    with pytest.raises(DaypartError, match="no days given"):
        parse_days("  ")
    with pytest.raises(DaypartError, match="not a day of the week"):
        parse_days("Mo")
    with pytest.raises(DaypartError, match="runs backwards"):
        parse_days("Su-Sa")


def test_days_print_back_the_way_they_were_written():
    for text in ("M-F", "Sa", "M-Su", "M,W,F", "Sa,Su"):
        assert show_days(parse_days(text)) == text
    assert show_days(frozenset()) == ""
    assert show_days(parse_days("M-W,F")) == "M-W,F"


def test_a_daypart_is_a_set_of_whole_hours_on_whole_days():
    drive = Daypart.build("AMD", "morning drive", "M-F", 6, 10)
    assert drive.hours_a_day == 4
    assert len(drive.cells) == 20
    assert drive.holds(MON, AirTime.at(6))
    assert drive.holds(MON, AirTime.at(9, 59, 59))
    assert not drive.holds(MON, AirTime.at(10))
    assert not drive.holds(SAT, AirTime.at(7))


def test_a_daypart_that_cannot_exist():
    with pytest.raises(DaypartError, match="needs a code"):
        Daypart.build("", "nameless", "M-F", 6, 10)
    with pytest.raises(DaypartError, match="runs on no days"):
        Daypart("AMD", "morning drive", frozenset(), 6, 10)
    with pytest.raises(DaypartError, match="starts at hour"):
        Daypart.build("AMD", "morning drive", "M-F", 24, 25)
    with pytest.raises(DaypartError, match="not after it"):
        Daypart.build("AMD", "morning drive", "M-F", 10, 10)
    with pytest.raises(DaypartError, match="not after it"):
        Daypart.build("EVE", "evening", "M-F", 19, 25)


def test_evening_runs_to_midnight_as_hour_twenty_four():
    evening = Daypart.build("EVE", "evening", "M-F", 19, 24)
    assert evening.holds(MON, AirTime.at(23, 59, 59))
    assert evening.span() == "7p-mid"


def test_a_daypart_reads_the_way_it_is_written_on_the_card():
    assert Daypart.build("AMD", "morning drive", "M-F", 6, 10).span() == "6a-10a"
    assert Daypart.build("MID", "midday", "M-F", 10, 15).span() == "10a-3p"
    assert Daypart.build("OVN", "overnight", "M-Su", 0, 6).span() == "mid-6a"
    assert Daypart.build("LUN", "lunch", "M-F", 11, 12).span() == "11a-noon"
    assert str(Daypart.build("AMD", "morning drive", "M-F", 6, 10)) == "M-F 6a-10a"


def test_overlap_and_containment_are_set_questions():
    grid = standard_grid()
    drive = grid.of("AMD")
    triple_a = grid.of("AAA")
    midday = grid.of("MID")
    assert drive.overlaps(triple_a)
    assert triple_a.contains_daypart(drive)
    assert not drive.contains_daypart(triple_a)
    assert not drive.overlaps(midday)
    assert grid.of("ROS").contains_daypart(triple_a)


def test_the_grid_will_not_take_the_same_code_twice():
    drive = Daypart.build("AMD", "morning drive", "M-F", 6, 10)
    with pytest.raises(DaypartError, match="on the grid twice"):
        DaypartGrid((drive, drive))


def test_the_grid_finds_a_daypart_by_code():
    grid = standard_grid()
    assert grid.of("PMD").name == "afternoon drive"
    assert grid.has("OVN")
    assert not grid.has("XXX")
    with pytest.raises(DaypartError, match="not on this grid"):
        grid.of("XXX")


def test_the_standard_grid_sells_every_hour_of_the_week():
    grid = standard_grid()
    assert len(grid.covered) == CELLS_IN_WEEK
    assert grid.gaps() == frozenset()
    assert grid.is_whole_week()


def test_a_grid_with_a_hole_in_it_says_which_hours():
    grid = DaypartGrid(
        (
            Daypart.build("AMD", "morning drive", "M-F", 6, 10),
            Daypart.build("PMD", "afternoon drive", "M-F", 15, 19),
        )
    )
    assert not grid.is_whole_week()
    assert (MON, 12) in grid.gaps()
    assert (MON, 7) not in grid.gaps()
    assert (SAT, 7) in grid.gaps()


def test_an_instant_lands_in_every_daypart_that_holds_it_narrowest_first():
    grid = standard_grid()
    covering = [daypart.code for daypart in grid.covering(MON, AirTime.at(7, 22))]
    assert covering == ["AMD", "AAA", "ROS"]
    assert [d.code for d in grid.covering(SAT, AirTime.at(7))] == ["SAT", "AAA", "ROS"]
    assert [d.code for d in grid.covering(MON, AirTime.at(2))] == ["OVN", "ROS"]
    assert [d.code for d in grid.covering(MON, AirTime.at(21))] == ["EVE", "ROS"]


def test_saturday_and_sunday_are_sold_apart_from_the_weekday_grid():
    grid = standard_grid()
    saturday = grid.of("SAT")
    assert saturday.days == frozenset({SAT})
    assert not saturday.overlaps(grid.of("AMD"))
    assert not saturday.overlaps(grid.of("SUN"))


def test_run_of_schedule_is_the_whole_week_and_holds_everything_else():
    grid = standard_grid()
    ros = grid.of("ROS")
    assert len(ros.cells) == CELLS_IN_WEEK
    for daypart in grid.dayparts:
        assert ros.contains_daypart(daypart)
