from datetime import date

import pytest

from cartwall.domain.political import (
    GENERAL_WINDOW,
    PRIMARY_WINDOW,
    Candidacy,
    Charge,
    ChargeBook,
    Election,
    PoliticalError,
    Race,
    Use,
    lowest_unit_charge,
    owed_back,
)
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE
IMMEDIATE = ClassOfTime.IMMEDIATE

# The 2026 Iowa primary and general, which is what the house is looking at.
PRIMARY_DAY = date(2026, 6, 2)
GENERAL_DAY = date(2026, 11, 3)


def a_race(kind=Election.GENERAL, day=GENERAL_DAY, office="Iowa House 63"):
    return Race(office=office, kind=kind, election_day=day)


def a_charge(rate="36.00", day=date(2026, 10, 1), **kw):
    return Charge(
        station=kw.pop("station", "KWLO"),
        daypart=kw.pop("daypart", "AMD"),
        grade=kw.pop("grade", IMMEDIATE),
        length=kw.pop("length", THIRTY),
        rate=Money.parse(rate),
        day=day,
        advertiser=kw.pop("advertiser", "HYVEE"),
    )


def test_a_primary_opens_a_forty_five_day_window():
    race = a_race(Election.PRIMARY, PRIMARY_DAY)
    assert race.kind.window_days == PRIMARY_WINDOW
    assert race.window_opens == date(2026, 4, 18)
    assert race.in_window(date(2026, 4, 18))
    assert not race.in_window(date(2026, 4, 17))


def test_a_general_opens_a_sixty_day_window():
    race = a_race()
    assert race.kind.window_days == GENERAL_WINDOW
    assert race.window_opens == date(2026, 9, 4)
    assert race.in_window(date(2026, 9, 4))
    assert not race.in_window(date(2026, 9, 3))


def test_a_runoff_gets_the_primary_window_and_a_special_gets_the_general_one():
    assert Election.RUNOFF.window_days == PRIMARY_WINDOW
    assert Election.SPECIAL.window_days == GENERAL_WINDOW


def test_election_day_itself_is_inside_the_window():
    race = a_race()
    assert race.in_window(GENERAL_DAY)
    assert not race.in_window(date(2026, 11, 4))
    assert race.days_left(date(2026, 10, 4)) == 30


def test_a_race_needs_an_office():
    with pytest.raises(PoliticalError, match="needs an office"):
        Race(office="", kind=Election.GENERAL, election_day=GENERAL_DAY)


def test_a_race_reads_the_way_the_public_file_prints_it():
    assert str(a_race()) == "Iowa House 63 general 11/03/2026"


def test_a_candidacy_needs_a_candidate_and_a_committee():
    with pytest.raises(PoliticalError, match="needs a candidate"):
        Candidacy(candidate="", race=a_race(), committee="Friends of Nobody")
    with pytest.raises(PoliticalError, match="names no committee"):
        Candidacy(candidate="R. Merrick", race=a_race(), committee="")


def test_a_candidate_inside_the_window_is_entitled_to_the_charge_rule():
    candidacy = Candidacy("R. Merrick", a_race(), "Merrick for Iowa")
    assert candidacy.entitled_on(date(2026, 10, 1), Use.CANDIDATE)
    assert str(candidacy) == "R. Merrick for Iowa House 63"


def test_a_candidate_outside_the_window_is_an_ordinary_advertiser():
    candidacy = Candidacy("R. Merrick", a_race(), "Merrick for Iowa")
    assert not candidacy.entitled_on(date(2026, 8, 1), Use.CANDIDATE)


def test_issue_advertising_is_never_entitled_however_political_it_sounds():
    candidacy = Candidacy("R. Merrick", a_race(), "Merrick for Iowa")
    assert not candidacy.entitled_on(date(2026, 10, 1), Use.ISSUE)
    assert not Use.ISSUE.entitled
    assert Use.CANDIDATE.entitled


def test_the_lowest_charge_is_what_somebody_paid_and_not_what_the_card_says():
    charges = [
        a_charge("36.00", advertiser="HYVEE"),
        a_charge("22.50", advertiser="SLUMB"),
        a_charge("30.00", advertiser="CASEY"),
    ]
    lowest = lowest_unit_charge(
        charges, "KWLO", "AMD", IMMEDIATE, THIRTY, date(2026, 9, 4), GENERAL_DAY
    )
    assert lowest == Money.parse("22.50")


def test_a_class_nobody_bought_has_no_lowest_charge_rather_than_a_zero():
    charges = [a_charge("36.00", grade=IMMEDIATE)]
    assert (
        lowest_unit_charge(charges, "KWLO", "AMD", FIXED, THIRTY, date(2026, 9, 4), GENERAL_DAY)
        is None
    )


def test_the_comparison_is_by_station_daypart_class_and_length():
    charges = [
        a_charge("10.00", station="KCRR"),
        a_charge("11.00", daypart="MID"),
        a_charge("12.00", grade=FIXED),
        a_charge("13.00", length=SIXTY),
        a_charge("36.00"),
    ]
    lowest = lowest_unit_charge(
        charges, "KWLO", "AMD", IMMEDIATE, THIRTY, date(2026, 9, 4), GENERAL_DAY
    )
    assert lowest == Money.parse("36.00")


def test_a_charge_outside_the_window_does_not_count():
    charges = [
        a_charge("10.00", day=date(2026, 8, 1)),
        a_charge("36.00", day=date(2026, 10, 1)),
    ]
    lowest = lowest_unit_charge(
        charges, "KWLO", "AMD", IMMEDIATE, THIRTY, date(2026, 9, 4), GENERAL_DAY
    )
    assert lowest == Money.parse("36.00")


def test_a_window_that_runs_backwards_is_refused():
    with pytest.raises(PoliticalError, match="runs backwards"):
        lowest_unit_charge([], "KWLO", "AMD", IMMEDIATE, THIRTY, GENERAL_DAY, date(2026, 9, 4))


def test_two_charges_are_comparable_when_they_are_the_same_kind_of_time():
    one = a_charge("36.00")
    same = a_charge("22.00")
    assert one.comparable_to(same)
    assert not one.comparable_to(a_charge("36.00", daypart="MID"))
    assert not one.comparable_to(a_charge("36.00", length=SIXTY))
    assert str(one) == "KWLO AMD immediately preemptible :30 $36.00"


def test_a_candidate_charged_above_the_lowest_is_owed_the_difference():
    assert owed_back(Money.dollars(36), Money.parse("22.50")) == Money.parse("13.50")


def test_a_candidate_charged_at_or_below_the_lowest_is_owed_nothing():
    assert owed_back(Money.parse("22.50"), Money.parse("22.50")) == Money(0)
    assert owed_back(Money.parse("20.00"), Money.parse("22.50")) == Money(0)


def test_nothing_is_owed_when_there_is_no_lowest_charge_to_measure_against():
    assert owed_back(Money.dollars(36), None) == Money(0)


def test_the_book_answers_for_a_whole_race_without_being_told_the_dates():
    book = ChargeBook([a_charge("36.00"), a_charge("22.50")])
    assert book.for_race(a_race(), "KWLO", "AMD", IMMEDIATE, THIRTY) == Money.parse("22.50")
    assert len(book) == 2


def test_a_charge_added_later_moves_the_answer():
    book = ChargeBook([a_charge("36.00")])
    race = a_race()
    assert book.for_race(race, "KWLO", "AMD", IMMEDIATE, THIRTY) == Money.dollars(36)
    book.add(a_charge("18.00", advertiser="SLUMB"))
    assert book.for_race(race, "KWLO", "AMD", IMMEDIATE, THIRTY) == Money.dollars(18)


def test_a_buyer_who_names_no_class_is_shown_the_cheapest_one_that_traded():
    book = ChargeBook(
        [
            a_charge("60.00", grade=FIXED),
            a_charge("48.00", grade=NOTICE),
            a_charge("36.00", grade=IMMEDIATE),
        ]
    )
    found = book.cheapest_class("KWLO", "AMD", THIRTY, date(2026, 9, 4), GENERAL_DAY)
    assert found == (IMMEDIATE, Money.dollars(36))


def test_the_dearest_class_wins_a_tie_because_it_is_the_better_promise():
    book = ChargeBook([a_charge("36.00", grade=FIXED), a_charge("36.00", grade=IMMEDIATE)])
    grade, rate = book.cheapest_class("KWLO", "AMD", THIRTY, date(2026, 9, 4), GENERAL_DAY)
    assert grade is FIXED
    assert rate == Money.dollars(36)


def test_a_station_that_traded_nothing_has_no_cheapest_class():
    book = ChargeBook([])
    assert book.cheapest_class("KWLO", "AMD", THIRTY, date(2026, 9, 4), GENERAL_DAY) is None
