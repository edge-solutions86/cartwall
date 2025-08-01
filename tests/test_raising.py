from datetime import date, datetime

import pytest

from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.day import DayLog
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import post
from cartwall.scheduling.inventory import DayBoard, Placement
from cartwall.services.makegood import Offer
from cartwall.services.owing import OwingError
from cartwall.services.raising import causes, owed_value, raise_owing, worst_lines
from support import CARD_DATE, MEMORY, MONDAY, a_grid

FIXED = ClassOfTime.FIXED
FRIDAY = date(2025, 8, 8)
SUNDAY = date(2025, 8, 10)
SIGNED = datetime(2025, 8, 1, 16, 30)
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def engine(cluster, accounts, cards, programming):
    engine = engine_for(MEMORY)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.cards.file(cards.in_force("KWLO", CARD_DATE))
        uow.stations.save_grid(programming["KWLO"])
        uow.commit()
    return engine


def an_order(number="25-1044", per_week=2, lines=2):
    return Order(
        number=number,
        advertiser="HYVEE",
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=tuple(
            Line(
                number=one,
                station="KWLO",
                daypart="AMD",
                grade=FIXED,
                length=THIRTY,
                rate=Money.parse("60.00"),
                flight=Flight.build(MONDAY, FRIDAY, "M-F", per_week),
            )
            for one in range(1, lines + 1)
        ),
    ).confirmed()


def a_day(uow, spots, asrun_lines, day=MONDAY):
    """One aired day with an as-run posted against it."""
    grid = a_grid("KWLO")
    board = DayBoard.build(grid, day)
    for hour, number, order, line in spots:
        board.avail(hour, number).add(
            Placement(
                order=order,
                line=line,
                advertiser="HYVEE",
                length=THIRTY,
                grade=FIXED,
                cart="C1204",
            )
        )
    logs = LogRepository(uow.session)
    logs.save(board)
    logs.approve("KWLO", day, "T. Ronan", SIGNED)
    logs.release("KWLO", day, SIGNED)
    logs.mark_aired("KWLO", day)
    rows = parse("\n".join([HEADER, *asrun_lines]) + "\n") if asrun_lines else []
    post(uow.session, reconcile(DayLog.build(grid, board), rows))


def ran(at, status="played", reference="25-1044-1", day="2025-08-04"):
    return f"{day},{at},C1204,a title,30.0,{status},{reference}"


def a_skipped_day(uow, lines=1):
    """A day where every spot on the log was skipped by the automation."""
    uow.orders.save(an_order(per_week=1, lines=lines))
    spots = [(7 + one * 2, 2, "25-1044", one + 1) for one in range(lines)]
    a_day(uow, spots, [])


def test_a_clean_night_raises_nothing(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1, lines=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00")])
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
    assert report.found == 0
    assert report.new == 0
    assert report.changed is False


def test_a_skipped_spot_is_put_on_file(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1, lines=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00", status="skip")])
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    assert report.new == 1
    assert report.changed is True
    with unit_of_work(engine) as uow:
        owed = uow.makegoods.ledger().still_owed()
    assert len(owed) == 1
    assert owed[0].order == "25-1044"
    assert owed[0].rate == Money.dollars(60)


def test_running_the_same_night_twice_does_not_owe_it_twice(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        first = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        second = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    assert first.new == 1
    assert second.new == 0
    assert second.found == 1
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 1


def test_the_report_says_why_the_debt_is_owed(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow, lines=2)
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
    assert report.by_cause == {"did not run": 2}


def test_a_span_that_runs_backwards_is_refused(engine):
    with unit_of_work(engine) as uow, pytest.raises(OwingError, match="backwards"):
        raise_owing(uow, SUNDAY, MONDAY)


def test_a_debt_the_gather_no_longer_finds_stays_on_file(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    with unit_of_work(engine) as uow:
        # The log is revised, so nothing is aired and the gather finds nothing.
        LogRepository(uow.session).revise("KWLO", MONDAY)
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    assert report.found == 0
    with unit_of_work(engine) as uow:
        assert uow.makegoods.count() == 1


def test_an_offer_whose_air_date_has_gone_by_is_expired_by_the_run(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        debt = uow.makegoods.ledger().debts[0]
        uow.makegoods.propose(debt, FRIDAY, "AMD", Money.dollars(60))
        uow.commit()
    with unit_of_work(engine) as uow:
        report = raise_owing(uow, MONDAY, date(2025, 8, 17), scheduled=False)
        uow.commit()
    assert report.expired == 1
    assert report.changed is True
    with unit_of_work(engine) as uow:
        assert uow.makegoods.open_offers() == []


def test_offers_are_expired_against_the_span_and_not_the_clock(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        debt = uow.makegoods.ledger().debts[0]
        uow.makegoods.propose(debt, date(2025, 8, 20), "AMD", Money.dollars(60))
        uow.commit()
    with unit_of_work(engine) as uow:
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    assert report.expired == 0
    with unit_of_work(engine) as uow:
        assert len(uow.makegoods.open_offers()) == 1


def test_expiring_can_be_pointed_at_a_day_of_its_own(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        debt = uow.makegoods.ledger().debts[0]
        uow.makegoods.propose(debt, date(2025, 8, 20), "AMD", Money.dollars(60))
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=False, today=date(2025, 9, 1))
    assert report.expired == 1


def test_an_accepted_offer_is_not_expired(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        debt = uow.makegoods.ledger().debts[0]
        made = uow.makegoods.propose(debt, FRIDAY, "AMD", Money.dollars(60))
        uow.makegoods.settle(made.id, Offer.ACCEPTED)
        report = raise_owing(uow, MONDAY, date(2025, 8, 17), scheduled=False)
    assert report.expired == 0
    assert made.state is Offer.ACCEPTED


def test_what_is_owed_is_worth_what_it_was_sold_at(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow, lines=2)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        uow.commit()
    with unit_of_work(engine) as uow:
        assert owed_value(uow) == Money.parse("120.00")
        assert owed_value(uow, order="25-1044") == Money.parse("120.00")
        assert owed_value(uow, order="25-9999") == Money(0)


def test_an_accepted_offer_takes_the_debt_off_the_total(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow, lines=2)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        debt = uow.makegoods.ledger().debts[0]
        made = uow.makegoods.propose(debt, FRIDAY, "AMD", Money.dollars(60))
        uow.makegoods.settle(made.id, Offer.ACCEPTED)
        uow.commit()
    with unit_of_work(engine) as uow:
        assert owed_value(uow) == Money.parse("60.00")


def test_the_worst_lines_come_back_worst_first(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=2, lines=2))
        a_day(uow, [(7, 2, "25-1044", 1), (9, 2, "25-1044", 1), (11, 2, "25-1044", 2)], [])
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        found = worst_lines(uow)
    assert found[0] == ("25-1044", 1, 2)
    assert found[1] == ("25-1044", 2, 1)


def test_the_worst_lines_can_be_cut_short(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=2, lines=2))
        a_day(uow, [(7, 2, "25-1044", 1), (11, 2, "25-1044", 2)], [])
        assert len(worst_lines(uow, limit=1)) == 0
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        assert len(worst_lines(uow, limit=1)) == 1


def test_every_cause_is_named_even_when_it_is_zero(engine):
    with unit_of_work(engine) as uow:
        a_skipped_day(uow)
        raise_owing(uow, MONDAY, SUNDAY, scheduled=False)
        found = causes(uow)
    assert found["did not run"] == 1
    assert found["bumped"] == 0
    assert found["never scheduled"] == 0


def test_the_never_scheduled_pass_finds_what_the_builder_never_placed(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=5, lines=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00")])
        report = raise_owing(uow, MONDAY, SUNDAY, scheduled=True)
        uow.commit()
    assert report.by_cause["never scheduled"] == 4
    with unit_of_work(engine) as uow:
        assert causes(uow)["never scheduled"] == 4
