import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.scheduling.candidates import Ask
from cartwall.scheduling.inventory import DayBoard, InventoryError, Placement
from cartwall.scheduling.lay import Lay
from cartwall.scheduling.pinning import (
    PinError,
    PinRefused,
    pin,
    pinned_count,
    pinned_on,
    put_spot,
    unpin,
)
from cartwall.scheduling.placer import place_week
from support import MONDAY

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE


@pytest.fixture
def lay(house):
    grid = house.programming["KWLO"]
    return Lay(DayBoard.build(grid, MONDAY), house.book("KWLO", MONDAY))


def an_ask(accounts, code="HYVEE", length=THIRTY, line=1, cart="C1204", daypart="AMD"):
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


def sit(lay, hour, number, code, line=1, length=THIRTY):
    lay.board.avail(hour, number).add(
        Placement(
            order="25-0100",
            line=line,
            advertiser=code,
            length=length,
            grade=IMMEDIATE,
        )
    )


def test_a_pin_puts_the_spot_in_the_break_it_names(lay, accounts):
    pinned = pin(lay, accounts, an_ask(accounts), 7, 5)
    assert pinned.landed.hour == 7
    assert pinned.landed.number == 5
    assert pinned.landed.cart == "C1204"
    assert not pinned.was_forced
    assert str(pinned).startswith("pinned to 2025-08-04 hour 7 break 5")


def test_a_pinned_spot_is_marked_so_nothing_moves_it(lay, accounts):
    pin(lay, accounts, an_ask(accounts), 7, 5)
    placement = lay.board.avail(7, 5).placements[0]
    assert placement.fixed
    assert pinned_count(lay) == 1
    assert pinned_on(lay) == [(7, 5, placement)]


def test_an_ordinary_placement_is_not_pinned(lay, accounts):
    put_spot(lay, lay.board.avail(7, 5), an_ask(accounts))
    assert not lay.board.avail(7, 5).placements[0].fixed
    assert pinned_count(lay) == 0


def test_a_pin_into_a_full_break_is_refused(lay, accounts):
    for line in range(1, 7):
        sit(lay, 7, 5, "SLUMB", line=line)
    with pytest.raises(PinRefused, match="has no room for a :30") as caught:
        pin(lay, accounts, an_ask(accounts), 7, 5)
    assert caught.value.code == "pin_refused"
    assert caught.value.context["number"] == 5


def test_a_pin_that_breaks_separation_is_refused_and_names_the_rules(lay, accounts):
    sit(lay, 7, 5, "DEALC")
    with pytest.raises(PinRefused, match="breaks SEP-CAT-BRK") as caught:
        pin(lay, accounts, an_ask(accounts, "DEALF"), 7, 5)
    assert "SEP-CAT-BRK" in caught.value.context["rules"]
    assert lay.board.avail(7, 5).used_units == 1


def test_a_written_reason_lets_the_traffic_director_do_it_anyway(lay, accounts):
    sit(lay, 7, 5, "DEALC")
    pinned = pin(
        lay,
        accounts,
        an_ask(accounts, "DEALF"),
        7,
        5,
        override="client bought the position, Chevy agreed",
    )
    assert pinned.was_forced
    assert pinned.override.startswith("client bought")
    assert {violation.rule.code for violation in pinned.broke} >= {"SEP-CAT-BRK"}
    assert "over 2 rule(s)" in str(pinned)
    assert lay.board.avail(7, 5).used_units == 2


def test_a_pin_that_breaks_nothing_ignores_an_override_that_was_offered(lay, accounts):
    pinned = pin(lay, accounts, an_ask(accounts), 7, 5, override="not needed")
    assert pinned.broke == ()


def test_the_builder_places_around_a_pin_rather_than_over_it(lay, accounts):
    pin(lay, accounts, an_ask(accounts, line=1), 7, 5)
    result = place_week([lay], accounts, an_ask(accounts, line=2, cart=""), 3)
    assert result.placed == 3
    assert lay.board.avail(7, 5).placements[0].fixed
    assert lay.board.count_for("25-1044", 1) == 1


def test_a_pinned_spot_still_counts_for_separation(lay, accounts):
    pin(lay, accounts, an_ask(accounts, line=1), 7, 5)
    result = place_week([lay], accounts, an_ask(accounts, line=2, cart=""), 4)
    hours = sorted(spot.hour for spot in result.landed)
    # Nothing else of this advertiser inside half an hour of 7:50.
    assert 8 not in hours or all(
        avail.hour != 8 or avail.number > 2
        for avail in lay.board.find("25-1044", 2)
    )


def test_unpinning_gives_the_unit_back(lay, accounts):
    pin(lay, accounts, an_ask(accounts), 7, 5)
    dropped = unpin(lay, "25-1044", 1, 7, 5)
    assert dropped.fixed
    assert lay.board.avail(7, 5).free_units == 6
    assert pinned_count(lay) == 0


def test_unpinning_something_that_is_not_there(lay, accounts):
    with pytest.raises(PinError, match="is not in KWLO hour 7 break 5"):
        unpin(lay, "25-1044", 1, 7, 5)
    with pytest.raises(InventoryError, match="no break 9"):
        unpin(lay, "25-1044", 1, 7, 9)


def test_pins_come_back_in_air_order(lay, accounts):
    pin(lay, accounts, an_ask(accounts, line=3), 9, 2)
    pin(lay, accounts, an_ask(accounts, line=1), 6, 3)
    pin(lay, accounts, an_ask(accounts, line=2), 7, 4)
    assert [(hour, number) for hour, number, _ in pinned_on(lay)] == [(6, 3), (7, 4), (9, 2)]


def test_a_sixty_pinned_into_a_break_with_one_unit_left_is_refused(lay, accounts):
    for line in range(1, 6):
        sit(lay, 7, 5, "SLUMB", line=line)
    with pytest.raises(PinRefused, match="no room for a 1:00"):
        pin(lay, accounts, an_ask(accounts, length=SIXTY), 7, 5)
    assert pin(lay, accounts, an_ask(accounts), 7, 5).landed.number == 5


def test_pinning_primes_the_neighbour_list_once(lay, accounts):
    sit(lay, 7, 2, "SLUMB")
    pin(lay, accounts, an_ask(accounts), 9, 2)
    assert len(lay.neighbours) == 2


def test_a_pin_counted_against_another_day_is_not_counted(lay, accounts):
    pin(lay, accounts, an_ask(accounts), 7, 5)
    assert pinned_count(lay, MONDAY) == 1
    assert pinned_count(lay, MONDAY.replace(day=5)) == 0
