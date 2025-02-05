from datetime import date

import pytest

from cartwall.airtime import AirTime, Duration
from cartwall.domain.account import Advertiser
from cartwall.domain.separation import (
    Candidate,
    Scope,
    SeparationBook,
    SeparationError,
    SeparationRule,
    Subject,
    house_rules,
)

MONDAY = date(2025, 8, 4)
TUESDAY = date(2025, 8, 5)

FORD = Advertiser.build("DEALF", "Dahl Ford", "AUTO.DOMESTIC.FORD", agency="MOP")
CHEV = Advertiser.build("DEALC", "Community Chevrolet", "AUTO.DOMESTIC.CHEV", agency="MOP")
GROUP = Advertiser.build("DEALG", "Dahl Auto Group", "AUTO")
COUCH = Advertiser.build("FURN", "Slumberland", "FURNITURE")


def spot(advertiser, hour, minute, break_number=2, cart="", day=MONDAY, agency=None):
    return Candidate(
        day=day,
        at=AirTime.at(hour, minute),
        break_number=break_number,
        advertiser=advertiser,
        cart=cart,
        agency=agency if agency is not None else advertiser.agency,
    )


def test_a_rule_that_cannot_be_filed():
    with pytest.raises(SeparationError, match="needs a code"):
        SeparationRule("", Subject.ADVERTISER, Scope.MINUTES, 30)
    with pytest.raises(SeparationError, match="names none"):
        SeparationRule("SEP", Subject.ADVERTISER, Scope.MINUTES, 0)
    with pytest.raises(SeparationError, match="scoped to the break, not minutes"):
        SeparationRule("SEP", Subject.CATEGORY, Scope.BREAK, 15)


def test_a_rule_reads_the_way_it_is_written_in_the_book():
    assert str(SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30)) == (
        "SEP-ADV: advertiser 30 minutes apart"
    )
    assert str(SeparationRule("SEP-CAT", Subject.CATEGORY, Scope.BREAK)) == (
        "SEP-CAT: category not twice in a break"
    )


def test_the_gap_between_two_spots_does_not_care_which_came_first():
    early = spot(FORD, 7, 10)
    late = spot(FORD, 7, 40)
    assert early.gap_to(late) == Duration.of_seconds(1800)
    assert late.gap_to(early) == Duration.of_seconds(1800)


def test_two_spots_on_different_days_have_no_gap_to_measure():
    with pytest.raises(SeparationError, match="different days"):
        spot(FORD, 7, 10).gap_to(spot(FORD, 7, 40, day=TUESDAY))


def test_the_advertiser_rule_catches_the_same_client_inside_half_an_hour():
    rule = SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30)
    assert rule.too_close(spot(FORD, 7, 10), spot(FORD, 7, 39))
    assert not rule.too_close(spot(FORD, 7, 10), spot(FORD, 7, 40))
    assert not rule.too_close(spot(FORD, 7, 10), spot(CHEV, 7, 15))


def test_a_rule_stops_at_the_end_of_the_day():
    rule = SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30)
    assert not rule.too_close(spot(FORD, 23, 55), spot(FORD, 0, 5, day=TUESDAY))


def test_the_category_rule_asks_the_conflict_codes_and_not_the_advertiser_code():
    rule = SeparationRule("SEP-CAT", Subject.CATEGORY, Scope.MINUTES, 15)
    # Two different dealers under one group code conflict through AUTO.
    assert rule.too_close(spot(FORD, 7, 10), spot(GROUP, 7, 20))
    # Two dealers with no shared code do not, however close they sit.
    assert not rule.too_close(spot(FORD, 7, 10), spot(CHEV, 7, 11))
    assert not rule.too_close(spot(FORD, 7, 10), spot(COUCH, 7, 11))


def test_the_break_rule_and_the_minute_rule_catch_different_things():
    in_break = SeparationRule("SEP-BRK", Subject.CATEGORY, Scope.BREAK)
    in_minutes = SeparationRule("SEP-MIN", Subject.CATEGORY, Scope.MINUTES, 4)

    # Same break, five minutes apart. The break rule catches it, minutes do not.
    one = spot(FORD, 7, 20, break_number=2)
    other = spot(GROUP, 7, 25, break_number=2)
    assert in_break.too_close(one, other)
    assert not in_minutes.too_close(one, other)

    # Different breaks, two minutes apart. Minutes catch it, the break rule does not.
    one = spot(FORD, 7, 22, break_number=2)
    other = spot(GROUP, 7, 24, break_number=3)
    assert not in_break.too_close(one, other)
    assert in_minutes.too_close(one, other)


def test_the_break_rule_does_not_confuse_break_two_of_one_hour_with_break_two_of_another():
    rule = SeparationRule("SEP-BRK", Subject.CATEGORY, Scope.BREAK)
    assert not rule.too_close(spot(FORD, 7, 20, break_number=2), spot(GROUP, 8, 20, break_number=2))


def test_the_hour_rule_catches_the_same_cart_wherever_it_sits_in_the_hour():
    rule = SeparationRule("SEP-CART", Subject.CART, Scope.HOUR)
    assert rule.too_close(
        spot(FORD, 7, 5, break_number=2, cart="C1204"),
        spot(FORD, 7, 55, break_number=5, cart="C1204"),
    )
    assert not rule.too_close(
        spot(FORD, 7, 5, cart="C1204"),
        spot(FORD, 8, 5, cart="C1204"),
    )
    assert not rule.too_close(
        spot(FORD, 7, 5, cart="C1204"),
        spot(FORD, 7, 55, cart="C1205"),
    )


def test_a_spot_with_no_cart_yet_never_trips_the_cart_rule():
    rule = SeparationRule("SEP-CART", Subject.CART, Scope.HOUR)
    assert not rule.too_close(spot(FORD, 7, 5), spot(FORD, 7, 55))


def test_the_agency_rule_only_fires_when_both_spots_have_the_same_agency():
    rule = SeparationRule("SEP-AGY", Subject.AGENCY, Scope.BREAK)
    assert rule.too_close(spot(FORD, 7, 20), spot(CHEV, 7, 25))
    assert not rule.too_close(spot(FORD, 7, 20), spot(GROUP, 7, 25))


def test_the_book_refuses_the_same_rule_code_twice():
    book = SeparationBook()
    book.file(SeparationRule("SEP-ADV", Subject.ADVERTISER, Scope.MINUTES, 30))
    with pytest.raises(SeparationError, match="already filed"):
        book.file(SeparationRule("SEP-ADV", Subject.CART, Scope.HOUR))
    with pytest.raises(SeparationError, match="rule SEP-XXX is not filed"):
        book.rule("SEP-XXX")


def test_the_house_book_files_four_rules():
    book = house_rules()
    assert len(book) == 4
    assert book.rule("SEP-ADV").minutes == 30
    assert book.rule("SEP-CAT-BRK").scope is Scope.BREAK
    assert book.rule("SEP-CART").scope is Scope.HOUR


def test_a_clean_placement_reports_nothing():
    book = house_rules()
    neighbours = [spot(COUCH, 7, 12, break_number=2), spot(CHEV, 7, 42, break_number=4)]
    assert book.allows(spot(FORD, 7, 25, break_number=3), neighbours)
    assert book.violations(spot(FORD, 7, 25, break_number=3), neighbours) == []


def test_one_bad_placement_can_break_more_than_one_rule_at_once():
    book = house_rules()
    neighbours = [spot(GROUP, 7, 22, break_number=2, cart="C9001")]
    found = book.violations(spot(FORD, 7, 24, break_number=2, cart="C9001"), neighbours)
    codes = sorted(violation.rule.code for violation in found)
    assert codes == ["SEP-CART", "SEP-CAT-BRK", "SEP-CAT-MIN"]


def test_a_violation_says_how_far_apart_they_were_when_the_rule_counts_minutes():
    book = house_rules()
    neighbours = [spot(FORD, 7, 10, break_number=2)]
    found = book.violations(spot(FORD, 7, 25, break_number=3), neighbours)
    # Fifteen minutes apart is far enough for the fifteen minute category rule,
    # which reads its bound as at least, so only the half hour rule fires.
    assert [v.rule.code for v in found] == ["SEP-ADV"]
    advertiser = found[0]
    assert advertiser.gap == Duration.of_seconds(900)
    assert "15:00 apart" in str(advertiser)


def test_a_break_scoped_violation_has_no_gap_to_report():
    book = house_rules()
    found = book.violations(
        spot(FORD, 7, 20, break_number=2),
        [spot(GROUP, 7, 20, break_number=2)],
    )
    breaks = [violation for violation in found if violation.rule.scope is Scope.BREAK]
    assert breaks[0].gap is None
    assert str(breaks[0]).endswith("DEALG at 7:20:00a")


def test_a_candidate_is_never_checked_against_itself():
    book = house_rules()
    candidate = spot(FORD, 7, 20, cart="C1204")
    assert book.allows(candidate, [candidate])
