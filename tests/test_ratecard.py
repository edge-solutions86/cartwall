from datetime import date
from decimal import Decimal

import pytest

from cartwall.domain.ratecard import (
    ClassOfTime,
    Quote,
    RateCard,
    RateCardBook,
    RateError,
)
from cartwall.domain.spot import SIXTY, THIRTY, SpotLength
from cartwall.money import Money

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE
IMMEDIATE = ClassOfTime.IMMEDIATE

JANUARY = date(2025, 1, 1)
JULY = date(2025, 7, 1)


def a_card(code="C25A", station="KWLO", effective=JANUARY):
    return RateCard.build(
        code,
        station,
        effective,
        [
            ("AMD", FIXED, 60),
            ("AMD", NOTICE, 48),
            ("AMD", IMMEDIATE, 36),
            ("MID", FIXED, 32),
            ("MID", NOTICE, 26),
            ("MID", IMMEDIATE, 20),
            ("ROS", IMMEDIATE, 12),
        ],
    )


def test_a_class_says_whether_the_spot_can_be_moved_and_how_much_warning_is_owed():
    assert not FIXED.may_be_preempted
    assert NOTICE.may_be_preempted
    assert IMMEDIATE.may_be_preempted
    assert FIXED.notice_days == 0
    assert NOTICE.notice_days == 14
    assert IMMEDIATE.notice_days == 0


def test_a_higher_class_outbids_a_lower_one():
    assert FIXED.rank > NOTICE.rank > IMMEDIATE.rank
    assert sorted([NOTICE, FIXED, IMMEDIATE], key=lambda grade: grade.rank)[0] is IMMEDIATE


def test_the_card_quotes_a_thirty_and_derives_the_rest():
    quote = Quote("AMD", FIXED, Money.dollars(60))
    assert quote.at_length(THIRTY) == Money.dollars(60)
    assert quote.at_length(SIXTY) == Money.dollars(Decimal("105.00"))
    assert quote.at_length(SpotLength(15)) == Money.dollars(39)
    assert quote.at_length(SpotLength(10)) == Money.dollars(30)


def test_a_sixty_is_not_two_thirties():
    quote = Quote("AMD", FIXED, Money.dollars(60))
    assert quote.at_length(SIXTY) < quote.at_length(THIRTY) * 2
    assert quote.at_length(SpotLength(15)) > Money(quote.at_length(THIRTY).cents // 2)


def test_the_length_factor_rounds_to_the_cent():
    quote = Quote("MID", NOTICE, Money.parse("26.00"))
    assert quote.at_length(SpotLength(15)) == Money.parse("16.90")
    assert quote.at_length(SpotLength(45)) == Money.parse("36.40")
    assert quote.at_length(SpotLength(90)) == Money.parse("65.00")


def test_a_quote_that_cannot_be_made():
    with pytest.raises(RateError, match="needs a daypart"):
        Quote("", FIXED, Money.dollars(60))
    with pytest.raises(RateError, match="quoted at -"):
        Quote("AMD", FIXED, Money.dollars(-1))


def test_the_card_looks_up_by_daypart_and_class_together():
    card = a_card()
    assert card.rate("AMD", FIXED, THIRTY) == Money.dollars(60)
    assert card.rate("AMD", IMMEDIATE, THIRTY) == Money.dollars(36)
    assert card.rate("MID", FIXED, THIRTY) == Money.dollars(32)
    assert card.rate("AMD", FIXED, SIXTY) == Money.parse("105.00")


def test_a_combination_the_card_does_not_quote_is_an_error_not_a_guess():
    card = a_card()
    with pytest.raises(RateError, match="does not quote ROS at fixed"):
        card.quote("ROS", FIXED)
    with pytest.raises(RateError, match="does not quote EVE"):
        card.quote("EVE", IMMEDIATE)
    assert card.covers("ROS")
    assert not card.covers("EVE")


def test_a_card_may_not_quote_the_same_cell_twice():
    with pytest.raises(RateError, match="quotes AMD fixed twice"):
        RateCard.build("DUP", "KWLO", JANUARY, [("AMD", FIXED, 60), ("AMD", FIXED, 55)])
    with pytest.raises(RateError, match="needs a code"):
        RateCard.build("", "KWLO", JANUARY, [])


def test_the_card_lists_its_dayparts_once_each_in_filing_order():
    assert a_card().dayparts() == ("AMD", "MID", "ROS")
    assert len(a_card()) == 7


def test_the_book_holds_every_card_a_station_ever_had():
    book = RateCardBook()
    book.file(a_card("C25A", effective=JANUARY))
    book.file(a_card("C25B", effective=JULY))
    book.file(a_card("K25A", station="KCRR", effective=JANUARY))
    assert [card.code for card in book.history("KWLO")] == ["C25A", "C25B"]
    assert [card.code for card in book.history("KCRR")] == ["K25A"]


def test_the_book_refuses_two_cards_for_one_station_on_one_day():
    book = RateCardBook()
    book.file(a_card("C25A"))
    with pytest.raises(RateError, match="already has card C25A"):
        book.file(a_card("C25X"))


def test_an_order_written_in_march_still_prices_off_the_january_card():
    book = RateCardBook()
    book.file(a_card("C25A", effective=JANUARY))
    book.file(a_card("C25B", effective=JULY))
    assert book.in_force("KWLO", date(2025, 3, 15)).code == "C25A"
    assert book.in_force("KWLO", date(2025, 6, 30)).code == "C25A"
    assert book.in_force("KWLO", JULY).code == "C25B"
    assert book.in_force("KWLO", date(2026, 1, 1)).code == "C25B"


def test_asking_for_a_rate_before_any_card_existed():
    book = RateCardBook()
    book.file(a_card("C25A", effective=JULY))
    with pytest.raises(RateError, match="no card in force"):
        book.in_force("KWLO", JANUARY)
    with pytest.raises(RateError, match="no card in force"):
        book.in_force("KCRR", JULY)


def test_the_book_prices_a_spot_from_station_date_daypart_class_and_length():
    book = RateCardBook()
    book.file(a_card("C25A", effective=JANUARY))
    assert book.rate("KWLO", date(2025, 3, 3), "AMD", NOTICE, SIXTY) == Money.dollars(84)
    assert book.rate("KWLO", date(2025, 3, 3), "ROS", IMMEDIATE, THIRTY) == Money.dollars(12)


def test_a_length_with_no_factor_filed_is_refused_rather_than_guessed():
    quote = Quote("AMD", FIXED, Money.dollars(60))

    class Odd:
        seconds = 25

        def __str__(self):
            return ":25"

    with pytest.raises(RateError, match="no length factor"):
        quote.at_length(Odd())
