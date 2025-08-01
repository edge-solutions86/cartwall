from datetime import date

import pytest

from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.makegoodstore import debt_from, offer_from
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.makegood import Cause, Debt, MakegoodError, NotOwed, Offer
from support import MEMORY

MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 8)
LATER = date(2025, 8, 18)


def a_line(number: int, station: str = "KWLO") -> Line:
    return Line(
        number=number,
        station=station,
        daypart="AMD",
        grade=ClassOfTime.FIXED,
        length=THIRTY,
        rate=Money.dollars(60),
        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
    )


@pytest.fixture
def engine(cluster, accounts):
    """A debt points at a real order, so one has to be on file first."""
    engine = engine_for(MEMORY)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.orders.save(
            Order(
                number="25-1044",
                advertiser="HYVEE",
                written=date(2025, 7, 21),
                salesperson="DHOLM",
                lines=(a_line(1), a_line(2), a_line(3, station="KCRR")),
            )
        )
        uow.commit()
    return engine


def a_debt(line=1, day=MONDAY, cause=Cause.DID_NOT_RUN, rate="60.00", daypart="AMD"):
    return Debt(
        order="25-1044",
        line=line,
        advertiser="HYVEE",
        station="KWLO",
        daypart=daypart,
        grade=ClassOfTime.FIXED,
        length=THIRTY,
        rate=Money.parse(rate),
        day=day,
        cause=cause,
    )


def test_a_raised_debt_reads_back_the_way_it_went_in(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        uow.commit()
    with unit_of_work(engine) as uow:
        found = uow.makegoods.ledger().debts
    assert found == [a_debt()]


def test_the_rate_survives_the_round_trip_as_cents(engine):
    with unit_of_work(engine) as uow:
        row = uow.makegoods.raise_debt(a_debt(rate="62.50"))
        assert row.rate == Money(6250)
        assert debt_from(row).rate == Money.parse("62.50")


def test_raising_the_same_debt_twice_does_not_owe_it_twice(engine):
    with unit_of_work(engine) as uow:
        first = uow.makegoods.raise_debt(a_debt())
        again = uow.makegoods.raise_debt(a_debt())
        uow.commit()
    assert first.id == again.id
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 1


def test_the_same_spot_owed_for_two_reasons_is_two_debts(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(cause=Cause.DID_NOT_RUN))
        uow.makegoods.raise_debt(a_debt(cause=Cause.BUMPED))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 2
        assert uow.makegoods.ledger().owed_by_line() == {("25-1044", 1): 2}


def test_a_batch_answers_how_many_were_new(engine):
    with unit_of_work(engine) as uow:
        assert uow.makegoods.raise_all([a_debt(line=1), a_debt(line=2)]) == 2
        assert uow.makegoods.raise_all([a_debt(line=2), a_debt(line=3)]) == 1
        uow.commit()


def test_an_offer_against_a_debt_nobody_raised_is_refused(engine):
    with unit_of_work(engine) as uow, pytest.raises(NotOwed, match="nothing is owed"):
        uow.makegoods.propose(a_debt(), FRIDAY, "AMD", Money.parse("60.00"))


def test_an_offer_reads_back_against_its_debt(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        row = uow.makegoods.propose(a_debt(), FRIDAY, "AAA", Money.parse("60.00"))
        uow.commit()
    with unit_of_work(engine) as uow:
        made = offer_from(uow.makegoods.open_offers()[0])
    assert made.debt == a_debt()
    assert made.daypart == "AAA"
    assert made.state is Offer.OPEN
    assert row.short is False


def test_an_offer_worth_less_than_the_spot_is_marked_short(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(rate="60.00"))
        row = uow.makegoods.propose(a_debt(rate="60.00"), FRIDAY, "MID", Money.parse("18.00"))
        assert row.short is True
        assert offer_from(row).shortfall == Money.parse("42.00")


def test_a_second_offer_while_one_is_open_is_refused(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        uow.makegoods.propose(a_debt(), FRIDAY, "AAA", Money.parse("60.00"))
        with pytest.raises(MakegoodError, match="already has an offer open"):
            uow.makegoods.propose(a_debt(), LATER, "AMD", Money.parse("60.00"))


def test_a_refused_offer_leaves_the_debt_open_for_another(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        first = uow.makegoods.propose(a_debt(), FRIDAY, "MID", Money.parse("18.00"))
        uow.makegoods.settle(first.id, Offer.REFUSED)
        second = uow.makegoods.propose(a_debt(), LATER, "AMD", Money.parse("60.00"))
        uow.commit()
    assert second.state is Offer.OPEN
    with unit_of_work(engine) as uow:
        ledger = uow.makegoods.ledger()
    assert len(ledger.offers) == 2
    assert ledger.still_owed() == [a_debt()]


def test_an_accepted_offer_closes_the_debt(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        made = uow.makegoods.propose(a_debt(), FRIDAY, "AMD", Money.parse("60.00"))
        uow.makegoods.settle(made.id, Offer.ACCEPTED)
        uow.commit()
    with unit_of_work(engine) as uow:
        ledger = uow.makegoods.ledger()
    assert ledger.still_owed() == []
    assert ledger.owed_value() == Money(0)


def test_settling_the_same_offer_twice_is_refused(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        made = uow.makegoods.propose(a_debt(), FRIDAY, "AMD", Money.parse("60.00"))
        uow.makegoods.settle(made.id, Offer.ACCEPTED)
        with pytest.raises(MakegoodError, match="already accepted"):
            uow.makegoods.settle(made.id, Offer.REFUSED)


def test_settling_an_offer_as_open_is_refused(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        made = uow.makegoods.propose(a_debt(), FRIDAY, "AMD", Money.parse("60.00"))
        with pytest.raises(MakegoodError, match="cannot be settled as open"):
            uow.makegoods.settle(made.id, Offer.OPEN)


def test_settling_an_offer_nobody_made_is_refused(engine):
    with unit_of_work(engine) as uow, pytest.raises(NotOwed, match="there is no offer 404"):
        uow.makegoods.settle(404, Offer.ACCEPTED)


def test_an_offer_whose_air_date_has_gone_by_is_withdrawn(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1))
        uow.makegoods.raise_debt(a_debt(line=2))
        uow.makegoods.propose(a_debt(line=1), FRIDAY, "AMD", Money.parse("60.00"))
        uow.makegoods.propose(a_debt(line=2), LATER, "AMD", Money.parse("60.00"))
        withdrawn = uow.makegoods.expire_before(date(2025, 8, 11))
        uow.commit()
    assert [row.day for row in withdrawn] == [FRIDAY]
    with unit_of_work(engine) as uow:
        assert [row.day for row in uow.makegoods.open_offers()] == [LATER]


def test_expiring_leaves_the_debt_owed(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt())
        uow.makegoods.propose(a_debt(), FRIDAY, "AMD", Money.parse("60.00"))
        uow.makegoods.expire_before(date(2025, 8, 11))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.ledger().still_owed() == [a_debt()]


def test_the_ledger_can_be_read_for_one_station(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1))
        uow.makegoods.raise_debt(
            Debt(
                order="25-1044",
                line=2,
                advertiser="HYVEE",
                station="KCRR",
                daypart="PMD",
                grade=ClassOfTime.FIXED,
                length=THIRTY,
                rate=Money.parse("40.00"),
                day=MONDAY,
                cause=Cause.DID_NOT_RUN,
            )
        )
        uow.commit()
    with unit_of_work(engine) as uow:
        assert [debt.line for debt in uow.makegoods.ledger(station="KWLO").debts] == [1]
        assert [debt.line for debt in uow.makegoods.ledger(station="KCRR").debts] == [2]
        assert len(uow.makegoods.ledger().debts) == 2


def test_the_ledger_comes_back_oldest_first(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=2, day=FRIDAY))
        uow.makegoods.raise_debt(a_debt(line=1, day=MONDAY))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert [debt.day for debt in uow.makegoods.ledger().debts] == [MONDAY, FRIDAY]


def test_what_was_owed_in_a_span_reads_back_by_day(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1, day=MONDAY))
        uow.makegoods.raise_debt(a_debt(line=2, day=FRIDAY))
        uow.makegoods.raise_debt(a_debt(line=3, day=LATER))
        uow.commit()
    with unit_of_work(engine) as uow:
        # Rows, not domain objects, so they are read while the session is open.
        assert [row.line for row in uow.makegoods.owed_between(MONDAY, FRIDAY)] == [1, 2]
        assert [row.line for row in uow.makegoods.owed_between(MONDAY, LATER)] == [1, 2, 3]


def test_the_open_debt_is_worth_what_it_was_sold_at(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1, rate="60.00"))
        uow.makegoods.raise_debt(a_debt(line=2, rate="45.00"))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.ledger().owed_value() == Money.parse("105.00")


def test_the_causes_are_counted_worst_first(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1, cause=Cause.DID_NOT_RUN))
        uow.makegoods.raise_debt(a_debt(line=2, cause=Cause.DID_NOT_RUN))
        uow.makegoods.raise_debt(a_debt(line=3, cause=Cause.BUMPED))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.ledger().by_cause() == {"did not run": 2, "bumped": 1}


def test_open_offers_can_be_read_for_one_order(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_debt(a_debt(line=1))
        uow.makegoods.propose(a_debt(line=1), FRIDAY, "AMD", Money.parse("60.00"))
        uow.commit()
    with unit_of_work(engine) as uow:
        assert len(uow.makegoods.open_offers("25-1044")) == 1
        assert uow.makegoods.open_offers("25-9999") == []


def test_four_identical_debts_stay_four(engine):
    """A line short four spots in a week is owed four, not one."""
    four = [a_debt(cause=Cause.NEVER_SCHEDULED) for _ in range(4)]
    with unit_of_work(engine) as uow:
        assert uow.makegoods.raise_all(four) == 4
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 4
        assert uow.makegoods.ledger().owed_by_line() == {("25-1044", 1): 4}


def test_gathering_the_same_four_again_changes_nothing(engine):
    four = [a_debt(cause=Cause.NEVER_SCHEDULED) for _ in range(4)]
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_all(four)
        assert uow.makegoods.raise_all(four) == 0
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 4


def test_a_shorter_gather_leaves_the_rest_on_file(engine):
    """Four owed last night and two tonight does not cancel the other two."""
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_all([a_debt(cause=Cause.NEVER_SCHEDULED) for _ in range(4)])
        assert uow.makegoods.raise_all([a_debt(cause=Cause.NEVER_SCHEDULED)] * 2) == 0
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 4


def test_each_of_the_four_is_offered_against_on_its_own(engine):
    with unit_of_work(engine) as uow:
        uow.makegoods.raise_all([a_debt(cause=Cause.NEVER_SCHEDULED) for _ in range(4)])
        debt = a_debt(cause=Cause.NEVER_SCHEDULED)
        first = uow.makegoods.propose(debt, FRIDAY, "AMD", Money.parse("60.00"), sequence=1)
        second = uow.makegoods.propose(debt, LATER, "AMD", Money.parse("60.00"), sequence=2)
        uow.commit()
    assert first.debt_id != second.debt_id
    with unit_of_work(engine) as uow:
        assert len(uow.makegoods.open_offers()) == 2
        assert len(uow.makegoods.ledger().still_owed()) == 4
