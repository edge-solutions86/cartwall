from datetime import date

import pytest

from cartwall.domain.daypart import standard_grid
from cartwall.domain.programming import ProgrammingError
from cartwall.domain.ratecard import ClassOfTime, RateError
from cartwall.domain.rulebook import House, RuleBook, RuleBookError
from cartwall.domain.separation import SeparationBook
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.domain.station import StationError
from cartwall.money import Money

from support import FRIDAY, MONDAY, SATURDAY

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE


def test_the_house_builds_a_book_for_a_station_on_a_date(house):
    book = house.book("KWLO", MONDAY)
    assert book.call == "KWLO"
    assert book.on == MONDAY
    assert book.card.code == "KWLO-25"
    assert book.station.commercial_load == 12


def test_a_book_is_pinned_to_its_date_and_nothing_inside_takes_another(house):
    book = house.book("KCNZ", MONDAY)
    assert book.clock(5).code == "FRM"
    saturday = house.book("KCNZ", SATURDAY)
    assert saturday.clock(5).code == "FRM"
    sunday = house.book("KCNZ", date(2025, 8, 10))
    assert sunday.clock(5).code == "TLK"


def test_the_book_reads_the_grid_for_its_own_weekday(house):
    friday = house.book("KCRR", FRIDAY)
    monday = house.book("KCRR", MONDAY)
    assert not friday.sells(20)
    assert monday.sells(20)
    assert 20 not in friday.selling_hours()
    assert len(friday.selling_hours()) == 20
    assert len(monday.selling_hours()) == 24


def test_asking_a_book_for_a_clock_in_a_preempted_hour(house):
    friday = house.book("KCRR", FRIDAY)
    with pytest.raises(ProgrammingError, match="preempted F at 20"):
        friday.clock(20)


def test_the_book_says_which_dayparts_an_hour_belongs_to_narrowest_first(house):
    monday = house.book("KWLO", MONDAY)
    assert monday.dayparts_for(7) == ("AMD", "AAA", "ROS")
    assert monday.dayparts_for(2) == ("OVN", "ROS")
    saturday = house.book("KWLO", SATURDAY)
    assert saturday.dayparts_for(7) == ("SAT", "AAA", "ROS")


def test_the_book_prices_off_the_card_that_was_in_force(house):
    book = house.book("KWLO", MONDAY)
    assert book.rate("AMD", FIXED, THIRTY) == Money.dollars(60)
    assert book.rate("AMD", FIXED, SIXTY) == Money.parse("105.00")
    assert book.rate("MID", NOTICE, THIRTY) == Money.dollars(26)
    with pytest.raises(RateError, match="does not quote EVE at fixed"):
        book.rate("EVE", FIXED, THIRTY)


def test_the_book_adds_up_the_inventory_the_day_actually_has(house):
    monday = house.book("KWLO", MONDAY)
    assert monday.units_available() == 24 * 24
    assert monday.commercial_minutes() == 24 * 12

    friday = house.book("KCRR", FRIDAY)
    assert friday.units_available() == 20 * 30
    assert friday.commercial_minutes() == 20 * 15


def test_the_farm_block_changes_what_one_station_has_to_sell(house):
    monday = house.book("KCNZ", MONDAY)
    # Two farm hours at 20 units, twenty two talk hours at 30.
    assert monday.units_available() == 2 * 20 + 22 * 30
    sunday = house.book("KCNZ", date(2025, 8, 10))
    assert sunday.units_available() == 24 * 30


def test_the_book_reports_hours_whose_clock_carries_more_than_the_licence_load(house):
    # KWLO says twelve minutes and runs the twelve minute music clock.
    assert house.book("KWLO", MONDAY).over_load() == []
    # KCRR says eighteen and runs the fifteen minute talk clock.
    assert house.book("KCRR", MONDAY).over_load() == []


def test_a_station_carrying_a_load_it_cannot_hold_is_reported_hour_by_hour(
    cluster, accounts, cards, programming
):
    thin = House(
        cluster=cluster,
        accounts=accounts,
        cards=cards,
        dayparts={"KWLO": standard_grid()},
        programming={"KWLO": programming["KWLO"]},
        separation={},
    )
    book = thin.book("KWLO", MONDAY)
    tighter = RuleBook(
        station=book.station.__class__(
            call="KWLO",
            band=book.station.band,
            frequency=book.station.frequency,
            city_of_license=book.station.city_of_license,
            service=book.station.service,
            commercial_load=8,
        ),
        on=MONDAY,
        dayparts=book.dayparts,
        programming=book.programming,
        separation=book.separation,
        card=book.card,
    )
    assert tighter.over_load() == list(range(24))


def test_a_station_with_no_separation_rules_filed_gets_an_empty_book(
    cluster, accounts, cards, programming
):
    bare = House(
        cluster=cluster,
        accounts=accounts,
        cards=cards,
        dayparts={"KWLO": standard_grid()},
        programming={"KWLO": programming["KWLO"]},
        separation={},
    )
    book = bare.book("KWLO", MONDAY)
    assert isinstance(book.separation, SeparationBook)
    assert len(book.separation) == 0


def test_a_translator_has_no_book_of_its_own_and_says_where_to_look(house):
    with pytest.raises(RuleBookError, match="carries KWLO, ask for that station"):
        house.book("KWLO-LP", MONDAY)
    assert house.logging_call("KWLO-LP") == "KWLO"
    assert house.logging_call("KWLO") == "KWLO"


def test_a_station_that_is_not_ours_has_no_book(house):
    with pytest.raises(StationError, match="not one of ours"):
        house.book("WHO", MONDAY)


def test_a_station_missing_a_table_is_refused_rather_than_defaulted(
    cluster, accounts, cards, programming
):
    partial = House(
        cluster=cluster,
        accounts=accounts,
        cards=cards,
        dayparts={},
        programming=programming,
        separation={},
    )
    with pytest.raises(RuleBookError, match="KWLO has no daypart grid filed"):
        partial.book("KWLO", MONDAY)

    no_grid = House(
        cluster=cluster,
        accounts=accounts,
        cards=cards,
        dayparts={"KWLO": standard_grid()},
        programming={},
        separation={},
    )
    with pytest.raises(RuleBookError, match="KWLO has no programming grid filed"):
        no_grid.book("KWLO", MONDAY)


def test_a_book_refuses_tables_that_belong_to_another_station(house):
    book = house.book("KWLO", MONDAY)
    other = house.book("KCRR", MONDAY)
    with pytest.raises(RuleBookError, match="was handed KCRR's programming"):
        RuleBook(
            station=book.station,
            on=MONDAY,
            dayparts=book.dayparts,
            programming=other.programming,
            separation=book.separation,
            card=book.card,
        )
    with pytest.raises(RuleBookError, match="was handed KCRR's rate card"):
        RuleBook(
            station=book.station,
            on=MONDAY,
            dayparts=book.dayparts,
            programming=book.programming,
            separation=book.separation,
            card=other.card,
        )


def test_the_house_hands_back_one_book_for_every_signal_that_gets_a_log(house):
    books = house.books(MONDAY)
    assert [book.call for book in books] == ["KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"]
    assert all(book.on == MONDAY for book in books)


def test_asking_for_a_book_before_any_rate_card_existed(house):
    with pytest.raises(RateError, match="no card in force"):
        house.book("KWLO", date(2024, 6, 1))
