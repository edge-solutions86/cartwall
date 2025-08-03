from datetime import date

import pytest

from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.money import Money
from cartwall.services.makegood import (
    Cause,
    Debt,
    Entry,
    Ledger,
    Makegood,
    MakegoodError,
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


def an_offer(debt=None, day=FRIDAY, daypart="AMD", rate="60.00", state=Offer.OPEN):
    """An offer in whatever state, since the ledger no longer moves them."""
    made = offer(debt or a_debt(), day, daypart, Money.parse(rate))
    return Makegood(
        debt=made.debt,
        day=made.day,
        daypart=made.daypart,
        rate=made.rate,
        state=state,
        short=made.short,
    )


def test_nothing_is_owed_until_a_debt_is_recorded():
    ledger = Ledger()
    assert len(ledger) == 0
    assert ledger.still_owed() == []
    assert ledger.owed_value() == Money(0)
    assert ledger.open_offers() == []


def test_a_debt_with_no_offer_is_still_owed():
    ledger = Ledger()
    ledger.owe(a_debt())
    assert ledger.still_owed() == [a_debt()]
    assert ledger.owed_value() == Money.dollars(60)


def test_an_open_offer_does_not_close_the_debt():
    ledger = Ledger()
    ledger.owe(a_debt(), [an_offer()])
    assert ledger.still_owed() == [a_debt()]
    assert len(ledger.open_offers()) == 1


def test_an_accepted_offer_closes_the_debt():
    ledger = Ledger()
    ledger.owe(a_debt(), [an_offer(state=Offer.ACCEPTED)])
    assert ledger.still_owed() == []
    assert ledger.owed_value() == Money(0)
    assert ledger.open_offers() == []


def test_a_refused_offer_leaves_the_debt_owed():
    ledger = Ledger()
    ledger.owe(a_debt(), [an_offer(state=Offer.REFUSED)])
    assert ledger.still_owed() == [a_debt()]


def test_an_expired_offer_leaves_the_debt_owed():
    ledger = Ledger()
    ledger.owe(a_debt(), [an_offer(state=Offer.EXPIRED)])
    assert ledger.still_owed() == [a_debt()]
    assert ledger.open_offers() == []


def test_a_refusal_then_an_acceptance_closes_it():
    ledger = Ledger()
    ledger.owe(
        a_debt(),
        [
            an_offer(daypart="OVN", rate="6.00", state=Offer.REFUSED),
            an_offer(day=NEXT_WEEK, state=Offer.ACCEPTED),
        ],
    )
    assert ledger.still_owed() == []


def test_four_identical_debts_are_four_entries():
    """One accepted makegood closes one of them, not all four."""
    ledger = Ledger()
    for _ in range(4):
        ledger.owe(a_debt(cause=Cause.NEVER_SCHEDULED))
    assert len(ledger) == 4
    assert ledger.owed_value() == Money.dollars(240)

    ledger.entries[0].offers.append(an_offer(state=Offer.ACCEPTED))
    assert len(ledger.still_owed()) == 3
    assert ledger.owed_value() == Money.dollars(180)


def test_an_entry_knows_which_of_its_offers_answered_it():
    entry = Entry(
        debt=a_debt(),
        offers=[an_offer(state=Offer.REFUSED), an_offer(day=NEXT_WEEK, state=Offer.ACCEPTED)],
    )
    assert entry.accepted is not None
    assert entry.accepted.day == NEXT_WEEK
    assert entry.open is None
    assert entry.is_owed is False


def test_an_entry_with_nothing_offered_is_owed_and_has_no_open_offer():
    entry = Entry(debt=a_debt())
    assert entry.is_owed is True
    assert entry.open is None
    assert entry.accepted is None


def test_an_entry_reads_as_the_debt_and_its_answer():
    assert str(Entry(debt=a_debt())).endswith("(nothing accepted)")


def test_the_debts_and_offers_read_across_every_entry():
    ledger = Ledger()
    ledger.owe(a_debt(line=1), [an_offer(state=Offer.REFUSED), an_offer()])
    ledger.owe(a_debt(line=2))
    assert [debt.line for debt in ledger.debts] == [1, 2]
    assert len(ledger.offers) == 2


def test_the_open_offers_come_back_soonest_first():
    ledger = Ledger()
    ledger.owe(a_debt(line=1), [an_offer(day=NEXT_WEEK)])
    ledger.owe(a_debt(line=2), [an_offer(day=FRIDAY)])
    assert [made.day for made in ledger.open_offers()] == [FRIDAY, NEXT_WEEK]


def test_what_is_owed_comes_back_oldest_first():
    ledger = Ledger()
    ledger.owe(a_debt(line=2, day=FRIDAY))
    ledger.owe(a_debt(line=1, day=MONDAY))
    assert [debt.day for debt in ledger.still_owed()] == [MONDAY, FRIDAY]


def test_the_worst_line_is_counted_first():
    ledger = Ledger()
    for _ in range(3):
        ledger.owe(a_debt(line=1))
    ledger.owe(a_debt(line=2))
    assert list(ledger.owed_by_line()) == [("25-1044", 1), ("25-1044", 2)]
    assert ledger.owed_by_line()[("25-1044", 1)] == 3


def test_a_closed_debt_leaves_its_line_out_of_the_tally():
    ledger = Ledger()
    ledger.owe(a_debt(line=1), [an_offer(state=Offer.ACCEPTED)])
    ledger.owe(a_debt(line=2))
    assert ledger.owed_by_line() == {("25-1044", 2): 1}


def test_the_causes_are_counted_worst_first():
    ledger = Ledger()
    ledger.owe(a_debt(line=1, cause=Cause.NEVER_SCHEDULED))
    ledger.owe(a_debt(line=2, cause=Cause.NEVER_SCHEDULED))
    ledger.owe(a_debt(line=3, cause=Cause.BUMPED))
    assert ledger.by_cause() == {"never scheduled": 2, "bumped": 1}


def test_a_ledger_is_worth_the_rate_each_spot_was_sold_at():
    ledger = Ledger()
    ledger.owe(a_debt(line=1, rate="60.00"))
    ledger.owe(a_debt(line=2, rate="12.50"))
    assert ledger.owed_value() == Money.parse("72.50")


def test_an_offer_earlier_than_the_debt_is_refused():
    with pytest.raises(MakegoodError, match="cannot run on"):
        offer(a_debt(day=FRIDAY), MONDAY, "AMD", Money.dollars(60))
