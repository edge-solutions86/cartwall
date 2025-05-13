import pytest

from cartwall.airtime import AirTime
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY, SpotLength
from cartwall.scheduling.candidates import (
    Ask,
    PlacementError,
    board_candidates,
    clean_openings,
    eligible_avails,
    openings,
    why_blocked,
    would_land_at,
)
from cartwall.scheduling.inventory import DayBoard, Placement
from support import MONDAY

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE


@pytest.fixture
def board(house):
    return DayBoard.build(house.programming["KWLO"], MONDAY)


@pytest.fixture
def book(house):
    return house.book("KWLO", MONDAY)


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


def put(board, hour, number, code, length=THIRTY, order="25-0900", line=1, cart=""):
    board.avail(hour, number).add(
        Placement(
            order=order,
            line=line,
            advertiser=code,
            length=length,
            grade=IMMEDIATE,
            cart=cart,
        )
    )


def test_an_ask_needs_an_order_and_a_line(accounts):
    with pytest.raises(PlacementError, match="names no order"):
        Ask(
            order="",
            line=1,
            advertiser=accounts.advertiser("HYVEE"),
            daypart="AMD",
            length=THIRTY,
            grade=FIXED,
        )
    with pytest.raises(PlacementError, match="not a line number"):
        an_ask(accounts, line=0)
    assert an_ask(accounts).key == ("25-1044", 1)
    assert str(an_ask(accounts)) == "25-1044-1 HYVEE :30 AMD"


def test_only_the_breaks_in_the_asked_daypart_are_eligible(board, book, accounts):
    found = eligible_avails(board, book, an_ask(accounts, daypart="AMD"))
    assert {avail.hour for avail in found} == {6, 7, 8, 9}
    assert len(found) == 16
    assert [(a.hour, a.number) for a in found][:2] == [(6, 2), (6, 3)]


def test_a_wider_daypart_offers_more_of_the_day(board, book, accounts):
    triple_a = eligible_avails(board, book, an_ask(accounts, daypart="AAA"))
    ros = eligible_avails(board, book, an_ask(accounts, daypart="ROS"))
    assert {avail.hour for avail in triple_a} == set(range(6, 19))
    assert len(ros) == len(board)


def test_a_daypart_the_station_does_not_sell_is_refused(board, book, accounts):
    with pytest.raises(PlacementError, match="does not sell XXX"):
        eligible_avails(board, book, an_ask(accounts, daypart="XXX"))


def test_a_full_break_stops_being_eligible(board, book, accounts):
    for line in range(1, 7):
        put(board, 7, 2, "SLUMB", line=line)
    found = eligible_avails(board, book, an_ask(accounts))
    assert (7, 2) not in [(a.hour, a.number) for a in found]
    assert (7, 3) in [(a.hour, a.number) for a in found]


def test_a_sixty_is_eligible_in_fewer_places_than_a_thirty(board, book, accounts):
    put(board, 7, 2, "SLUMB", length=SIXTY, line=1)
    put(board, 7, 2, "VGM", length=SIXTY, line=2)
    put(board, 7, 2, "BLACK", length=THIRTY, line=3)
    thirties = [(a.hour, a.number) for a in eligible_avails(board, book, an_ask(accounts))]
    sixties = [
        (a.hour, a.number) for a in eligible_avails(board, book, an_ask(accounts, length=SIXTY))
    ]
    assert (7, 2) in thirties
    assert (7, 2) not in sixties


def test_a_spot_lands_behind_what_is_already_in_the_break(board):
    avail = board.avail(7, 2)
    assert would_land_at(avail, THIRTY) == AirTime.at(7, 10)
    put(board, 7, 2, "SLUMB", length=SIXTY)
    assert would_land_at(avail, THIRTY) == AirTime.at(7, 11)
    put(board, 7, 2, "VGM", length=SIXTY, line=2)
    assert would_land_at(avail, SIXTY) == AirTime.at(7, 12)


def test_a_length_that_will_not_fit_lands_nowhere(board):
    put(board, 7, 2, "SLUMB", length=SIXTY, line=1)
    put(board, 7, 2, "VGM", length=SIXTY, line=2)
    put(board, 7, 2, "BLACK", length=SIXTY, line=3)
    assert would_land_at(board.avail(7, 2), THIRTY) is None


def test_the_board_turns_into_separation_candidates_with_their_air_times(board, accounts):
    put(board, 7, 2, "HYVEE", length=SIXTY)
    put(board, 7, 2, "DEALF", line=2)
    put(board, 9, 4, "CASEY", line=3)
    around = board_candidates(board, accounts)
    assert len(around) == 3
    assert [candidate.at for candidate in around[:2]] == [
        AirTime.at(7, 10),
        AirTime.at(7, 11),
    ]
    assert around[1].advertiser.code == "DEALF"
    assert around[1].agency == "MOP"
    assert around[2].at == AirTime.at(9, 38)


def test_an_empty_board_offers_every_break_clean(board, book, accounts):
    found = openings(board, book, accounts, an_ask(accounts))
    assert len(found) == 16
    assert all(opening.is_clean for opening in found)
    assert str(found[0]).endswith("clean")


def test_a_break_holding_the_same_advertiser_is_offered_but_not_clean(board, book, accounts):
    put(board, 7, 2, "HYVEE")
    found = {(o.hour, o.number): o for o in openings(board, book, accounts, an_ask(accounts))}
    assert not found[(7, 2)].is_clean
    assert "SEP-ADV" in found[(7, 2)].reasons()
    assert found[(9, 2)].is_clean


def test_the_half_hour_rule_reaches_past_the_break_it_was_broken_in(board, book, accounts):
    put(board, 7, 5, "HYVEE")
    found = openings(board, book, accounts, an_ask(accounts))
    blocked = {(o.hour, o.number) for o in found if not o.is_clean}
    # 7:50 plus thirty minutes reaches into the 8 o'clock hour.
    assert (8, 2) in blocked
    assert (8, 3) not in blocked


def test_a_competing_car_dealer_blocks_the_break_and_the_fifteen_minutes_around_it(
    board, book, accounts
):
    put(board, 7, 3, "DEALC")
    every = openings(board, book, accounts, an_ask(accounts, "DEALF"))
    found = {(o.hour, o.number): o for o in every}
    assert not found[(7, 3)].is_clean
    assert "SEP-CAT-BRK" in found[(7, 3)].reasons()
    assert "SEP-CAT-MIN" in found[(7, 3)].reasons()
    # Break 4 is at :38, sixteen minutes after :22. Far enough.
    assert found[(7, 4)].is_clean


def test_an_unrelated_advertiser_never_blocks_anything(board, book, accounts):
    put(board, 7, 2, "SLUMB")
    put(board, 7, 3, "BLACK")
    assert all(o.is_clean for o in openings(board, book, accounts, an_ask(accounts, "DEALF")))


def test_the_same_cart_blocks_the_whole_hour_it_is_in(board, book, accounts):
    put(board, 7, 2, "HYVEE", cart="C1204")
    ask = an_ask(accounts, cart="C1204")
    found = openings(board, book, accounts, ask)
    # Isolate the cart rule. The advertiser rule blocks a wider window and the
    # point here is that the cart rule blocks its hour and stops there.
    assert {o.hour for o in found if "SEP-CART" in o.reasons()} == {7}


def test_clean_openings_are_the_ones_the_builder_may_use(board, book, accounts):
    put(board, 7, 2, "HYVEE")
    every = openings(board, book, accounts, an_ask(accounts))
    clean = clean_openings(board, book, accounts, an_ask(accounts))
    assert len(clean) < len(every)
    assert all(opening.is_clean for opening in clean)


def test_the_exception_report_says_which_rule_cost_the_most_openings(board, book, accounts):
    put(board, 7, 2, "HYVEE")
    put(board, 7, 3, "HYVEE")
    put(board, 8, 2, "HYVEE")
    tally = why_blocked(openings(board, book, accounts, an_ask(accounts)))
    assert "SEP-ADV" in tally
    assert tally["SEP-ADV"] >= tally.get("SEP-CAT-BRK", 0)
    assert list(tally) == sorted(tally, key=lambda code: (-tally[code], code))


def test_a_board_and_a_book_that_do_not_belong_together_are_refused(board, house, accounts):
    other_station = house.book("KCRR", MONDAY)
    with pytest.raises(PlacementError, match="rules were handed KWLO's board"):
        openings(board, other_station, accounts, an_ask(accounts))

    other_day = house.book("KWLO", MONDAY.replace(day=5))
    with pytest.raises(PlacementError, match="was handed a board for 2025-08-04"):
        openings(board, other_day, accounts, an_ask(accounts))


def test_a_short_spot_is_eligible_where_a_long_one_is_not(board, book, accounts):
    put(board, 7, 2, "SLUMB", length=SIXTY, line=1)
    put(board, 7, 2, "VGM", length=SIXTY, line=2)
    put(board, 7, 2, "BLACK", length=THIRTY, line=3)
    ask = an_ask(accounts, length=SpotLength(10))
    tens = [(o.hour, o.number) for o in openings(board, book, accounts, ask)]
    assert (7, 2) in tens
