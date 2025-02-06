from datetime import date

import pytest

from cartwall.domain.clock import ClockError, music_clock, talk_clock
from cartwall.domain.daypart import Weekday
from cartwall.domain.programming import (
    Assignment,
    ClockGrid,
    ProgrammingError,
    Source,
)

MON = Weekday.MON
SAT = Weekday.SAT
FRI = Weekday.FRI

JANUARY = date(2025, 1, 1)


@pytest.fixture
def grid():
    grid = ClockGrid("KWLO", JANUARY)
    grid.file_clock(music_clock())
    grid.file_clock(talk_clock())
    grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"))
    grid.assign(Assignment.build("farm block", "M-F", 5, 6, "TLK"))
    grid.assign(Assignment.preempt("friday football", "F", 19, 23))
    return grid


def test_an_assignment_that_cannot_be_filed():
    with pytest.raises(ProgrammingError, match="needs a label"):
        Assignment.build("", "M-F", 6, 10, "MUS")
    with pytest.raises(ProgrammingError, match="runs on no days"):
        Assignment("all week", frozenset(), 6, 10, "MUS")
    with pytest.raises(ProgrammingError, match="not an hour span"):
        Assignment.build("backwards", "M-F", 10, 6, "MUS")
    with pytest.raises(ProgrammingError, match="not an hour span"):
        Assignment.build("too late", "M-F", 20, 25, "MUS")


def test_a_selling_assignment_needs_a_clock_and_a_preemption_must_not_have_one():
    with pytest.raises(ProgrammingError, match="names no clock"):
        Assignment.build("nameless", "M-F", 6, 10)
    with pytest.raises(ProgrammingError, match="preempted but names clock"):
        Assignment.build("football", "F", 19, 23, "MUS", Source.PREEMPTED)


def test_an_assignment_covers_the_hours_it_was_written_for():
    farm = Assignment.build("farm block", "M-F", 5, 6, "TLK")
    assert farm.covers(MON, 5)
    assert not farm.covers(MON, 6)
    assert not farm.covers(SAT, 5)
    assert farm.hours == 5
    assert str(farm) == "farm block M-F 5-6 TLK"
    assert str(Assignment.preempt("football", "F", 19, 23)) == "football F 19-23 preempted"


def test_a_grid_will_not_take_a_clock_it_does_not_have(grid):
    with pytest.raises(ProgrammingError, match="which is not filed"):
        grid.assign(Assignment.build("sports", "Sa", 12, 16, "SPT"))


def test_a_grid_will_not_file_the_same_clock_twice(grid):
    with pytest.raises(ClockError, match="already filed"):
        grid.file_clock(music_clock())


def test_the_last_assignment_filed_is_the_one_that_wins(grid):
    assert grid.clock_for(MON, 7).code == "MUS"
    assert grid.clock_for(MON, 5).code == "TLK"
    assert grid.clock_for(SAT, 5).code == "MUS"


def test_order_matters_and_a_grid_read_backwards_schedules_into_a_ballgame():
    backwards = ClockGrid("KWLO", JANUARY)
    backwards.file_clock(music_clock())
    backwards.assign(Assignment.preempt("friday football", "F", 19, 23))
    backwards.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"))
    assert backwards.sells(FRI, 20)


def test_a_preempted_hour_has_no_clock_and_says_why(grid):
    assert not grid.sells(FRI, 20)
    assert grid.sells(FRI, 18)
    assert grid.sells(FRI, 23)
    assert grid.sells(SAT, 20)
    with pytest.raises(ProgrammingError, match="preempted F at 20 for friday football"):
        grid.clock_for(FRI, 20)


def test_an_hour_nobody_programmed_is_a_gap_and_not_a_guess():
    thin = ClockGrid("KCRR", JANUARY)
    thin.file_clock(music_clock())
    thin.assign(Assignment.build("weekdays", "M-F", 6, 19, "MUS"))
    assert not thin.is_complete()
    assert (MON, 5) in thin.gaps()
    assert (SAT, 12) in thin.gaps()
    assert (MON, 7) not in thin.gaps()
    with pytest.raises(ProgrammingError, match="nothing programmed M at 5"):
        thin.slot(MON, 5)
    assert not thin.sells(MON, 5)


def test_a_full_grid_reports_no_gaps(grid):
    assert grid.is_complete()
    assert grid.gaps() == []


def test_asking_about_an_hour_that_is_not_an_hour(grid):
    with pytest.raises(ProgrammingError, match="hour 24 is not in the day"):
        grid.slot(MON, 24)
    with pytest.raises(ProgrammingError, match="hour -1 is not in the day"):
        grid.slot(MON, -1)


def test_the_grid_counts_the_hours_it_actually_sells(grid):
    # 168 hours less the four preempted on Friday night.
    assert grid.selling_hours() == 164


def test_the_grid_adds_up_every_local_unit_it_puts_on_the_air():
    plain = ClockGrid("KCNZ", JANUARY)
    plain.file_clock(music_clock())
    plain.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"))
    assert plain.units_a_week() == 168 * 24


def test_the_farm_block_and_the_ballgame_both_change_the_weekly_unit_count(grid):
    # 164 selling hours: five of them are talk at 30 units, the rest music at 24.
    assert grid.units_a_week() == 5 * 30 + 159 * 24
