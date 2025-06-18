from datetime import date

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.errors import ConflictError
from cartwall.money import Money
from cartwall.services.makegood import (
    Cause,
    Debt,
    Ledger,
    MakegoodError,
    NotOwed,
    Offer,
    offer,
)

FIXED = ClassOfTime.FIXED
MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 8)
NEXT_WEEK = date(2025, 8, 11)


def a_debt(line=1, day=MONDAY, rate="60.00", cause=Cause.DID_NOT_RUN, daypart="AMD"):
    return Debt(
        order="25-1044",
        line=line,
        advertiser="HYVEE",
        station="KWLO",
        daypart=daypart,
        grade=FIXED,
        length=THIRTY,
        rate=Money.parse(rate),
        day=day,
        cause=cause,
    )


def test_a_debt_says_what_was_lost_and_why():
    debt = a_debt()
    assert debt.key == ("25-1044", 1)
    assert str(debt) == "25-1044-1 KWLO 2025-08-04 did not run, worth $60.00"
    assert a_debt(cause=Cause.BUMPED).cause is Cause.BUMPED


def test_an_offer_worth_the_same_is_not_short():
    made = offer(a_debt(), FRIDAY, "AMD", Money.dollars(60))
    assert not made.short
    assert made.shortfall == Money(0)
    assert made.state is Offer.OPEN


def test_an_offer_worth_less_is_marked_rather_than_quietly_booked():
    made = offer(a_debt(), FRIDAY, "OVN", Money.dollars(6))
    assert made.short
    assert made.shortfall == Money.dollars(54)
    assert "short" in str(made)


def test_an_offer_worth_more_is_not_short_and_owes_nothing_back():
    made = offer(a_debt(), FRIDAY, "AMD", Money.dollars(75))
    assert not made.short
    assert made.shortfall == Money(0)


def test_a_makegood_cannot_run_before_the_spot_it_replaces():
    with pytest.raises(MakegoodError, match="cannot run on 2025-08-01") as caught:
        offer(a_debt(), date(2025, 8, 1), "AMD", Money.dollars(60))
    assert caught.value.context == {"owed": "2025-08-04", "offered": "2025-08-01"}


def test_a_makegood_on_the_same_day_is_allowed():
    assert offer(a_debt(), MONDAY, "AMD", Money.dollars(60)).day == MONDAY


def test_an_offer_needs_a_daypart():
    with pytest.raises(MakegoodError, match="needs a daypart"):
        offer(a_debt(), FRIDAY, "", Money.dollars(60))


def test_an_offer_reads_the_way_the_seller_reads_it():
    made = offer(a_debt(), FRIDAY, "MID", Money.dollars(32))
    assert str(made) == "25-1044-1 offered MID on 2025-08-08 at $32.00 short (open)"


def test_nothing_is_owed_until_a_debt_is_recorded():
    ledger = Ledger()
    assert len(ledger) == 0
    assert ledger.still_owed() == []
    assert ledger.owed_value() == Money(0)


def test_a_debt_with_no_offer_is_still_owed():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    assert ledger.still_owed() == [debt]
    assert ledger.owed_value() == Money.dollars(60)


def test_an_offer_that_is_open_does_not_settle_the_debt():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    assert ledger.still_owed() == [debt]


def test_an_accepted_offer_settles_the_debt():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    made = ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    ledger.settle(made, Offer.ACCEPTED)
    assert ledger.still_owed() == []
    assert ledger.owed_value() == Money(0)


def test_a_refused_offer_leaves_the_debt_open():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    made = ledger.propose(debt, FRIDAY, "OVN", Money.dollars(6))
    ledger.settle(made, Offer.REFUSED)
    assert ledger.still_owed() == [debt]
    assert ledger.open_offer_for(debt) is None


def test_a_refused_offer_can_be_followed_by_a_better_one():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    first = ledger.propose(debt, FRIDAY, "OVN", Money.dollars(6))
    ledger.settle(first, Offer.REFUSED)
    second = ledger.propose(debt, NEXT_WEEK, "AMD", Money.dollars(60))
    ledger.settle(second, Offer.ACCEPTED)
    assert ledger.still_owed() == []


def test_two_offers_cannot_be_open_on_one_debt_at_once():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    with pytest.raises(ConflictError, match="already has an offer open"):
        ledger.propose(debt, NEXT_WEEK, "AMD", Money.dollars(60))


def test_an_offer_against_a_debt_nobody_recorded_is_refused():
    ledger = Ledger()
    with pytest.raises(NotOwed, match="nothing is owed on 25-1044-1"):
        ledger.propose(a_debt(), FRIDAY, "AMD", Money.dollars(60))


def test_an_answer_cannot_be_given_twice():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    made = ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    settled = ledger.settle(made, Offer.ACCEPTED)
    with pytest.raises(MakegoodError, match="already accepted"):
        ledger.settle(settled, Offer.REFUSED)


def test_settling_an_offer_the_ledger_never_saw():
    ledger = Ledger()
    with pytest.raises(MakegoodError, match="not on this ledger"):
        ledger.settle(offer(a_debt(), FRIDAY, "AMD", Money.dollars(60)), Offer.ACCEPTED)


def test_what_is_still_owed_comes_back_oldest_first():
    ledger = Ledger()
    for day, line in ((FRIDAY, 2), (MONDAY, 1), (MONDAY, 3)):
        ledger.owe(a_debt(line=line, day=day))
    assert [(debt.day, debt.line) for debt in ledger.still_owed()] == [
        (MONDAY, 1),
        (MONDAY, 3),
        (FRIDAY, 2),
    ]


def test_the_worst_hit_line_is_reported_first():
    ledger = Ledger()
    for line, times in ((1, 3), (2, 1)):
        for offset in range(times):
            ledger.owe(a_debt(line=line, day=date(2025, 8, 4 + offset)))
    owed = ledger.owed_by_line()
    assert list(owed) == [("25-1044", 1), ("25-1044", 2)]
    assert owed[("25-1044", 1)] == 3


def test_the_debt_is_counted_by_what_caused_it():
    ledger = Ledger()
    ledger.owe(a_debt(line=1, cause=Cause.DID_NOT_RUN))
    ledger.owe(a_debt(line=2, cause=Cause.BUMPED))
    ledger.owe(a_debt(line=3, cause=Cause.BUMPED))
    ledger.owe(a_debt(line=4, cause=Cause.NEVER_SCHEDULED))
    assert ledger.by_cause() == {"bumped": 2, "did not run": 1, "never scheduled": 1}


def test_the_open_debt_is_worth_what_it_was_sold_at():
    ledger = Ledger()
    ledger.owe(a_debt(line=1, rate="60.00"))
    ledger.owe(a_debt(line=2, rate="32.00"))
    assert ledger.owed_value() == Money.dollars(92)


def test_an_offer_whose_day_has_passed_is_withdrawn():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    withdrawn = ledger.expire_before(NEXT_WEEK)
    assert len(withdrawn) == 1
    assert withdrawn[0].state is Offer.EXPIRED
    assert ledger.open_offer_for(debt) is None
    assert ledger.still_owed() == [debt]


def test_an_offer_still_in_the_future_is_left_alone():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    ledger.propose(debt, NEXT_WEEK, "AMD", Money.dollars(60))
    assert ledger.expire_before(FRIDAY) == []
    assert ledger.open_offer_for(debt) is not None


def test_an_accepted_offer_is_not_expired_out_from_under_it():
    ledger = Ledger()
    debt = a_debt()
    ledger.owe(debt)
    made = ledger.propose(debt, FRIDAY, "AMD", Money.dollars(60))
    ledger.settle(made, Offer.ACCEPTED)
    assert ledger.expire_before(NEXT_WEEK) == []
    assert ledger.still_owed() == []
