from datetime import date, timedelta

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.rotation import Rotation
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.scheduling.candidates import Ask, board_candidates
from cartwall.scheduling.inventory import DayBoard, Placement
from cartwall.scheduling.placer import Lay, PlacerError, place_week, spread
from support import MONDAY

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE

WEEKDAYS = [MONDAY + timedelta(days=offset) for offset in range(5)]


@pytest.fixture
def lays(house):
    grid = house.programming["KWLO"]
    return [Lay(DayBoard.build(grid, day), house.book("KWLO", day)) for day in WEEKDAYS]


def an_ask(accounts, code="HYVEE", daypart="AMD", length=THIRTY, cart="", line=1):
    advertiser = accounts.advertiser(code)
    return Ask(
        order="25-1044",
        line=line,
        advertiser=advertiser,
        daypart=daypart,
        length=length,
        grade=FIXED,
        cart=cart,
        agency=advertiser.agency,
    )


def assert_no_violations(lays, accounts):
    """Nothing the placer put down may break a rule it was checking."""
    for lay in lays:
        around = board_candidates(lay.board, accounts)
        for candidate in around:
            others = [other for other in around if other is not candidate]
            broken = lay.book.separation.violations(candidate, others)
            assert not broken, f"{candidate} broke {[str(v) for v in broken]}"


def fill(lay, code, hours=(6, 7, 8, 9), numbers=(2, 3, 4, 5), each=6):
    """Pack the morning drive breaks of one day so nothing else fits."""
    line = 1
    for hour in hours:
        for number in numbers:
            for _ in range(each):
                lay.board.avail(hour, number).add(
                    Placement(
                        order="25-0001",
                        line=line,
                        advertiser=code,
                        length=THIRTY,
                        grade=IMMEDIATE,
                    )
                )
                line += 1


def test_spreading_a_count_over_buckets_puts_the_extras_at_the_front():
    assert spread(12, 5) == [3, 3, 2, 2, 2]
    assert spread(10, 5) == [2, 2, 2, 2, 2]
    assert spread(3, 5) == [1, 1, 1, 0, 0]
    assert spread(0, 5) == [0, 0, 0, 0, 0]
    assert sum(spread(47, 7)) == 47


def test_spreading_over_nothing_or_below_nothing_is_refused():
    with pytest.raises(PlacerError, match="no buckets"):
        spread(12, 0)
    with pytest.raises(PlacerError, match="negative count"):
        spread(-1, 5)


def test_a_lay_refuses_a_board_and_rules_that_do_not_match(house):
    grid = house.programming["KWLO"]
    with pytest.raises(PlacerError, match="came with rules for"):
        Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", WEEKDAYS[1]))
    with pytest.raises(PlacerError, match="board came with KCRR's rules"):
        Lay(DayBoard.build(grid, MONDAY), house.book("KCRR", MONDAY))


def test_twelve_a_week_lands_three_three_two_two_two(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts), 12)
    assert result.placed == 12
    assert result.is_complete
    assert [result.by_day()[day] for day in WEEKDAYS] == [3, 3, 2, 2, 2]


def test_everything_lands_inside_the_daypart_that_was_bought(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts, daypart="AMD"), 20)
    assert set(result.hours()) <= {6, 7, 8, 9}
    assert result.placed == 20


def test_three_spots_in_one_day_land_in_three_different_hours(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts), 12)
    monday = [spot for spot in result.landed if spot.day == MONDAY]
    assert len(monday) == 3
    assert len({spot.hour for spot in monday}) == 3


def test_a_heavy_schedule_keeps_spreading_rather_than_stacking_one_hour(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts), 20)
    assert result.placed == 20
    for day in WEEKDAYS:
        hours = sorted(spot.hour for spot in result.landed if spot.day == day)
        assert hours == [6, 7, 8, 9]


def test_a_daypart_runs_out_of_room_long_before_it_runs_out_of_units(lays, accounts):
    # Morning drive is sixteen breaks a day and ninety six units, but the half
    # hour rule means one advertiser cannot have more than about six of them.
    result = place_week(lays, accounts, an_ask(accounts), 40)
    assert result.placed < 40
    assert result.short > 0
    per_day = result.by_day()
    assert max(per_day.values()) <= 6
    for miss in result.missed:
        assert "SEP-ADV" in miss.blocked


def test_nothing_lands_where_the_half_hour_rule_would_be_broken(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts), 20)
    for day in WEEKDAYS:
        spots = sorted(
            (spot for spot in result.landed if spot.day == day), key=lambda s: s.hour
        )
        hours = [spot.hour for spot in spots]
        assert len(set(hours)) == len(hours), "two in one hour breaks the half hour rule"


def test_a_day_that_cannot_take_its_share_spills_onto_its_neighbours(lays, accounts):
    fill(lays[2], "SLUMB")
    result = place_week(lays, accounts, an_ask(accounts), 10)
    assert result.placed == 10
    assert result.is_complete
    tally = result.by_day()
    assert tally.get(WEEKDAYS[2], 0) == 0
    assert sum(tally.values()) == 10


def test_the_spill_goes_to_the_nearest_day_that_can_take_it(lays, accounts):
    fill(lays[0], "SLUMB")
    result = place_week(lays, accounts, an_ask(accounts), 5)
    tally = result.by_day()
    assert tally.get(MONDAY, 0) == 0
    assert tally[WEEKDAYS[1]] == 2


def test_a_week_with_nowhere_left_reports_a_miss_for_every_spot(lays, accounts):
    for lay in lays:
        fill(lay, "SLUMB")
    result = place_week(lays, accounts, an_ask(accounts), 4)
    assert result.placed == 0
    assert result.short == 4
    assert not result.is_complete
    assert all("no break in the daypart had room" in str(miss) for miss in result.missed)


def test_a_miss_caused_by_separation_names_the_rules_that_did_it(lays, accounts):
    # Fill every drive break on one day with the same advertiser we are placing,
    # leaving one unit free in each so there is room but no clean room.
    for hour in (6, 7, 8, 9):
        for number in (2, 3, 4, 5):
            for line in range(1, 6):
                lays[0].board.avail(hour, number).add(
                    Placement(
                        order="25-0001",
                        line=line,
                        advertiser="HYVEE",
                        length=THIRTY,
                        grade=IMMEDIATE,
                    )
                )
    for lay in lays[1:]:
        fill(lay, "SLUMB")
    result = place_week(lays, accounts, an_ask(accounts), 1)
    assert result.short == 1
    miss = result.missed[0]
    assert "every open break was blocked" in str(miss)
    assert "SEP-ADV" in miss.blocked


def test_a_flight_with_no_days_in_this_week_misses_the_whole_lot(accounts):
    result = place_week([], accounts, an_ask(accounts), 6)
    assert result.placed == 0
    assert result.short == 1
    assert "no air days" in str(result.missed[0])


def test_placing_nothing_places_nothing_and_complains_about_nothing(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts), 0)
    assert result.placed == 0
    assert result.is_complete


def test_placing_a_negative_count_is_refused(lays, accounts):
    with pytest.raises(PlacerError, match="negative number"):
        place_week(lays, accounts, an_ask(accounts), -1)


def test_a_rotation_hands_the_carts_out_across_the_week(lays, accounts):
    rotation = Rotation.even("C1204", "C1205", "C1206")
    result = place_week(lays, accounts, an_ask(accounts), 12, rotation)
    carts = [spot.cart for spot in result.landed]
    assert sorted(carts).count("C1204") == 4
    assert sorted(carts).count("C1205") == 4
    assert sorted(carts).count("C1206") == 4


def test_with_no_rotation_every_spot_carries_the_asks_own_cart(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts, cart="C1204"), 5)
    assert {spot.cart for spot in result.landed} == {"C1204"}


def test_sixties_take_two_units_each_and_still_place(lays, accounts):
    result = place_week(lays, accounts, an_ask(accounts, length=SIXTY), 10)
    assert result.placed == 10
    assert result.is_complete


def test_two_lines_of_the_same_advertiser_keep_out_of_each_others_way(lays, accounts):
    first = place_week(lays, accounts, an_ask(accounts, line=1), 10)
    second = place_week(lays, accounts, an_ask(accounts, line=2), 10)
    assert first.placed == 10
    assert second.placed == 10
    assert_no_violations(lays, accounts)


def test_competing_dealers_do_not_land_in_the_same_break(lays, accounts):
    ford = place_week(lays, accounts, an_ask(accounts, "DEALF"), 15)
    chev = place_week(lays, accounts, an_ask(accounts, "DEALC", line=2), 15)
    assert ford.placed == 15
    assert chev.placed == 15
    breaks = [(spot.day, spot.hour, spot.number) for spot in ford.landed + chev.landed]
    assert len(set(breaks)) == len(breaks)
    assert_no_violations(lays, accounts)


def test_the_result_sorts_into_air_order_however_the_spill_ran(lays, accounts):
    fill(lays[1], "SLUMB")
    result = place_week(lays, accounts, an_ask(accounts), 9)
    keys = [(spot.day, spot.hour, spot.number) for spot in result.landed]
    assert keys == sorted(keys)


def test_a_saturday_line_places_on_the_saturday_grid(house, accounts):
    saturday = date(2025, 8, 9)
    grid = house.programming["KWLO"]
    lays = [Lay(DayBoard.build(grid, saturday), house.book("KWLO", saturday))]
    result = place_week(lays, accounts, an_ask(accounts, daypart="SAT"), 6)
    assert result.placed == 6
    assert set(result.hours()) <= set(range(6, 24))
