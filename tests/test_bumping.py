from datetime import date

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.scheduling.bumping import (
    BumpError,
    BumpQueue,
    NothingToBump,
    bump_into,
    enough_to_bump,
    may_bump,
    preemptible,
)
from cartwall.scheduling.candidates import Ask
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE
IMMEDIATE = ClassOfTime.IMMEDIATE

# The board is 2025-08-04. Three weeks earlier is well inside the notice window.
LONG_BEFORE = date(2025, 7, 14)
DAY_BEFORE = date(2025, 8, 3)


@pytest.fixture
def board(house):
    return DayBoard.build(house.programming["KWLO"], MONDAY)


@pytest.fixture
def avail(board):
    return board.avail(7, 2)


def an_ask(accounts, code="DEALF", grade=FIXED, length=THIRTY, line=1):
    advertiser = accounts.advertiser(code)
    return Ask(
        order="25-9000",
        line=line,
        advertiser=advertiser,
        daypart="AMD",
        length=length,
        grade=grade,
        agency=advertiser.agency,
    )


def sit(avail, grade=IMMEDIATE, length=THIRTY, line=1, code="SLUMB", fixed=False):
    placement = Placement(
        order="25-0100",
        line=line,
        advertiser=code,
        length=length,
        grade=grade,
        fixed=fixed,
    )
    avail.add(placement)
    return placement


def test_a_higher_class_may_take_a_unit_off_a_lower_one():
    assert may_bump(IMMEDIATE, FIXED, MONDAY, LONG_BEFORE)
    assert may_bump(IMMEDIATE, NOTICE, MONDAY, LONG_BEFORE)
    assert may_bump(NOTICE, FIXED, MONDAY, LONG_BEFORE)


def test_an_equal_class_never_bumps_however_much_money_is_on_it():
    for grade in (FIXED, NOTICE, IMMEDIATE):
        assert not may_bump(grade, grade, MONDAY, LONG_BEFORE)


def test_a_lower_class_never_bumps_a_higher_one():
    assert not may_bump(FIXED, NOTICE, MONDAY, LONG_BEFORE)
    assert not may_bump(FIXED, IMMEDIATE, MONDAY, LONG_BEFORE)
    assert not may_bump(NOTICE, IMMEDIATE, MONDAY, LONG_BEFORE)


def test_fixed_position_is_never_bumped_at_any_notice():
    assert not may_bump(FIXED, FIXED, MONDAY, LONG_BEFORE)
    assert not may_bump(FIXED, FIXED, MONDAY, date(2020, 1, 1))


def test_the_notice_class_needs_a_fortnight_and_counts_it_to_the_air_date():
    # 2025-08-04 less fourteen days is 2025-07-21.
    assert may_bump(NOTICE, FIXED, MONDAY, date(2025, 7, 21))
    assert not may_bump(NOTICE, FIXED, MONDAY, date(2025, 7, 22))
    assert not may_bump(NOTICE, FIXED, MONDAY, DAY_BEFORE)


def test_the_immediate_class_can_be_bumped_the_morning_of():
    assert may_bump(IMMEDIATE, FIXED, MONDAY, MONDAY)
    assert may_bump(IMMEDIATE, NOTICE, MONDAY, MONDAY)


def test_what_is_preemptible_comes_back_cheapest_promise_first(avail, accounts):
    sit(avail, NOTICE, line=1)
    sit(avail, IMMEDIATE, line=2)
    sit(avail, IMMEDIATE, SIXTY, line=3)
    found = preemptible(avail, an_ask(accounts), LONG_BEFORE)
    assert [placement.line for placement in found] == [3, 2, 1]


def test_a_pinned_spot_is_not_preemptible_even_at_the_cheapest_class(avail, accounts):
    sit(avail, IMMEDIATE, line=1, fixed=True)
    sit(avail, IMMEDIATE, line=2)
    found = preemptible(avail, an_ask(accounts), LONG_BEFORE)
    assert [placement.line for placement in found] == [2]


def test_a_break_with_room_needs_nothing_bumped(avail, accounts):
    sit(avail, IMMEDIATE, line=1)
    assert enough_to_bump(avail, an_ask(accounts), LONG_BEFORE) == []


def test_one_thirty_comes_off_to_make_room_for_a_thirty(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    taken = enough_to_bump(avail, an_ask(accounts), LONG_BEFORE)
    assert len(taken) == 1
    assert taken[0].length == THIRTY


def test_one_sixty_comes_off_rather_than_two_thirties(avail, accounts):
    sit(avail, IMMEDIATE, SIXTY, line=1)
    sit(avail, IMMEDIATE, SIXTY, line=2)
    sit(avail, IMMEDIATE, SIXTY, line=3)
    taken = enough_to_bump(avail, an_ask(accounts, length=SIXTY), LONG_BEFORE)
    assert len(taken) == 1
    assert taken[0].length == SIXTY


def test_two_thirties_come_off_when_a_sixty_needs_the_room(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    taken = enough_to_bump(avail, an_ask(accounts, length=SIXTY), LONG_BEFORE)
    assert len(taken) == 2
    assert all(placement.length == THIRTY for placement in taken)


def test_the_cheapest_promise_goes_first_even_when_it_is_shorter(avail, accounts):
    sit(avail, NOTICE, SIXTY, line=1)
    sit(avail, NOTICE, SIXTY, line=2)
    sit(avail, IMMEDIATE, THIRTY, line=3)
    sit(avail, IMMEDIATE, THIRTY, line=4)
    taken = enough_to_bump(avail, an_ask(accounts), LONG_BEFORE)
    assert [placement.line for placement in taken] == [3]


def test_a_break_of_fixed_position_spots_cannot_be_bumped_into(avail, accounts):
    for line in range(1, 7):
        sit(avail, FIXED, line=line)
    assert enough_to_bump(avail, an_ask(accounts), LONG_BEFORE) is None


def test_a_break_of_notice_spots_inside_the_window_cannot_be_bumped_into(avail, accounts):
    for line in range(1, 7):
        sit(avail, NOTICE, line=line)
    assert enough_to_bump(avail, an_ask(accounts), DAY_BEFORE) is None
    assert enough_to_bump(avail, an_ask(accounts), LONG_BEFORE) is not None


def test_bumping_puts_the_new_spot_in_and_takes_the_old_one_out(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    removed = bump_into(avail, an_ask(accounts), LONG_BEFORE)
    assert len(removed) == 1
    assert avail.holds("25-9000", 1)
    assert not avail.holds("25-0100", removed[0].placement.line)
    assert len(avail) == 6


def test_what_came_off_says_who_took_it_and_from_where(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    removed = bump_into(avail, an_ask(accounts), LONG_BEFORE)
    bumped = removed[0]
    assert bumped.day == MONDAY
    assert bumped.hour == 7
    assert bumped.number == 2
    assert bumped.by_order == "25-9000"
    assert "for 25-9000-1" in str(bumped)


def test_a_break_that_will_not_yield_refuses_and_says_why(avail, accounts):
    for line in range(1, 7):
        sit(avail, FIXED, line=line)
    with pytest.raises(NothingToBump, match="may be moved for a fixed spot") as caught:
        bump_into(avail, an_ask(accounts), LONG_BEFORE)
    assert caught.value.code == "nothing_to_bump"
    assert caught.value.context["hour"] == 7


def test_a_day_that_has_already_aired_cannot_be_bumped_into(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    with pytest.raises(BumpError, match="already aired"):
        bump_into(avail, an_ask(accounts), date(2025, 8, 5))


def test_the_queue_remembers_who_is_owed_what(avail, accounts):
    queue = BumpQueue()
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    bump_into(avail, an_ask(accounts, line=1), LONG_BEFORE, queue=queue)
    bump_into(avail, an_ask(accounts, line=2), LONG_BEFORE, queue=queue)
    assert len(queue) == 2
    owed = queue.owed()
    assert sum(owed.values()) == 2
    assert all(order == "25-0100" for order, _ in owed)
    assert len(queue.for_order("25-0100")) == 2
    assert len(queue.for_order("25-9000")) == 0
    assert len(queue.on(MONDAY)) == 2
    assert len(queue.on(date(2025, 8, 5))) == 0


def test_the_queue_reports_the_worst_hit_line_first(board, accounts):
    queue = BumpQueue()
    # One line filling three breaks loses three, another filling two loses two.
    breaks = {1: [(7, 2), (7, 3), (7, 4)], 2: [(7, 5), (8, 2)], 3: [(8, 3)]}
    incoming = 1
    for line, places in breaks.items():
        for hour, number in places:
            avail = board.avail(hour, number)
            for _ in range(6):
                sit(avail, IMMEDIATE, line=line)
            bump_into(avail, an_ask(accounts, line=incoming), LONG_BEFORE, queue=queue)
            incoming += 1
    owed = queue.owed()
    assert list(owed.values()) == [3, 2, 1]
    assert owed[("25-0100", 1)] == 3
    assert len(queue.for_line("25-0100", 2)) == 2
    assert len(queue) == 6


def test_a_spot_that_bumps_carries_the_cart_it_was_given(avail, accounts):
    for line in range(1, 7):
        sit(avail, IMMEDIATE, line=line)
    bump_into(avail, an_ask(accounts), LONG_BEFORE, cart="C1204")
    landed = [p for p in avail.placements if p.order == "25-9000"][0]
    assert landed.cart == "C1204"
    assert landed.grade is FIXED
