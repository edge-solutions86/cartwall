from datetime import date, datetime

import pytest

from cartwall.broadcast import BroadcastWeek
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.day import DayLog
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import BumpRepository, LogRepository
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import post
from cartwall.scheduling.bumping import Bumped
from cartwall.scheduling.inventory import DayBoard, Placement
from cartwall.services.makegood import Cause
from cartwall.services.owing import OwingError, bumped, did_not_run, gather, never_scheduled
from support import CARD_DATE, MEMORY, MONDAY, a_grid

FIXED = ClassOfTime.FIXED
WEEK = BroadcastWeek(MONDAY)
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


def an_order(number="25-1044", per_week=2, confirm=True, rate="60.00"):
    order = Order(
        number=number,
        advertiser="HYVEE",
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=(
            Line(
                number=1,
                station="KWLO",
                daypart="AMD",
                grade=FIXED,
                length=THIRTY,
                rate=Money.parse(rate),
                flight=Flight.build(MONDAY, FRIDAY, "M-F", per_week),
            ),
        ),
    )
    return order.confirmed() if confirm else order


def a_day(uow, spots, asrun_lines, day=MONDAY, air=True):
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
    if not air:
        return
    logs.approve("KWLO", day, "T. Ronan", SIGNED)
    logs.release("KWLO", day, SIGNED)
    logs.mark_aired("KWLO", day)
    rows = parse("\n".join([HEADER, *asrun_lines]) + "\n") if asrun_lines else []
    post(uow.session, reconcile(DayLog.build(grid, board), rows))


def ran(at, status="played", reference="25-1044-1", day="2025-08-04"):
    return f"{day},{at},C1204,a title,30.0,{status},{reference}"


def test_a_spot_that_ran_owes_nothing(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00")])
        assert did_not_run(uow, MONDAY, SUNDAY) == []


def test_a_spot_the_automation_skipped_is_owed(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00", status="skip")])
        owed = did_not_run(uow, MONDAY, SUNDAY)
    assert len(owed) == 1
    assert owed[0].cause is Cause.DID_NOT_RUN
    assert owed[0].rate == Money.dollars(60)
    assert owed[0].station == "KWLO"
    assert owed[0].daypart == "AMD"


def test_a_day_nobody_posted_an_as_run_for_owes_nothing_yet(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [], air=False)
        assert did_not_run(uow, MONDAY, SUNDAY) == []


def test_a_cancelled_order_owes_nothing_however_much_did_not_run(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00", status="skip")])
        uow.orders.save(an_order(per_week=1).cancelled())
        assert did_not_run(uow, MONDAY, SUNDAY) == []


def test_a_spot_for_a_line_that_is_no_longer_on_the_order_is_skipped(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        a_day(uow, [(7, 2, "25-1044", 9)], [])
        assert did_not_run(uow, MONDAY, SUNDAY) == []


def test_a_bump_nobody_has_made_good_is_owed(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        BumpRepository(uow.session).record(
            "KWLO",
            Bumped(
                placement=Placement(
                    order="25-1044",
                    line=1,
                    advertiser="HYVEE",
                    length=THIRTY,
                    grade=FIXED,
                ),
                day=MONDAY,
                hour=7,
                number=2,
                by_order="25-9000",
                by_line=1,
            ),
        )
        uow.session.flush()
        owed = bumped(uow, MONDAY, SUNDAY)
    assert len(owed) == 1
    assert owed[0].cause is Cause.BUMPED
    assert owed[0].rate == Money.dollars(60)


def test_a_bump_that_has_been_made_good_is_not_owed_again(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=1))
        repo = BumpRepository(uow.session)
        row = repo.record(
            "KWLO",
            Bumped(
                placement=Placement(
                    order="25-1044",
                    line=1,
                    advertiser="HYVEE",
                    length=THIRTY,
                    grade=FIXED,
                ),
                day=MONDAY,
                hour=7,
                number=2,
                by_order="25-9000",
                by_line=1,
            ),
        )
        uow.session.flush()
        repo.settle(row.id)
        assert bumped(uow, MONDAY, SUNDAY) == []


def test_a_line_that_was_fully_scheduled_owes_nothing(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=2))
        a_day(uow, [(7, 2, "25-1044", 1), (9, 2, "25-1044", 1)], [], air=False)
        assert never_scheduled(uow, WEEK) == []


def test_a_line_the_builder_could_not_place_is_owed_the_difference(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=4))
        a_day(uow, [(7, 2, "25-1044", 1)], [], air=False)
        owed = never_scheduled(uow, WEEK)
    assert len(owed) == 3
    assert all(debt.cause is Cause.NEVER_SCHEDULED for debt in owed)
    assert owed[0].rate == Money.dollars(60)


def test_a_line_with_no_log_at_all_owes_its_whole_week(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=3))
        assert len(never_scheduled(uow, WEEK)) == 3


def test_a_pending_order_owes_nothing_until_it_is_confirmed(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=3, confirm=False))
        assert never_scheduled(uow, WEEK) == []


def test_a_week_the_flight_does_not_touch_owes_nothing(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=3))
        assert never_scheduled(uow, BroadcastWeek(date(2025, 9, 1))) == []


def test_the_gather_puts_all_three_sources_on_one_ledger(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=3))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00", status="skip")])
        BumpRepository(uow.session).record(
            "KWLO",
            Bumped(
                placement=Placement(
                    order="25-1044",
                    line=1,
                    advertiser="HYVEE",
                    length=THIRTY,
                    grade=FIXED,
                ),
                day=FRIDAY,
                hour=7,
                number=2,
                by_order="25-9000",
                by_line=1,
            ),
        )
        uow.session.flush()
        ledger = gather(uow, MONDAY, SUNDAY)
    # One skipped, one bumped, and two of the three a week never scheduled.
    assert ledger.by_cause() == {
        "did not run": 1,
        "never scheduled": 2,
        "bumped": 1,
    }
    assert len(ledger) == 4
    assert ledger.owed_value() == Money.dollars(240)


def test_the_expensive_pass_can_be_turned_off(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=3))
        a_day(uow, [(7, 2, "25-1044", 1)], [ran("07:10:00", status="skip")])
        ledger = gather(uow, MONDAY, SUNDAY, scheduled=False)
    assert ledger.by_cause() == {"did not run": 1}


def test_a_span_that_runs_backwards_is_refused(engine):
    with (
        unit_of_work(engine) as uow,
        pytest.raises(OwingError, match="runs backwards"),
    ):
        gather(uow, SUNDAY, MONDAY)


def test_a_span_covering_two_weeks_counts_each_week_once(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(per_week=2))
        ledger = gather(uow, MONDAY, date(2025, 8, 17))
    # The flight only runs in the first week, so only that week is short.
    assert ledger.by_cause() == {"never scheduled": 2}
