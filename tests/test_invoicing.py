from datetime import date, datetime
from decimal import Decimal

import pytest

from cartwall.broadcast import BroadcastMonth
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.logs.day import DayLog
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.logstore import LogRepository
from cartwall.persistence.models.billing import InvoiceRow
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.playout.asrun import parse
from cartwall.reconcile.matcher import reconcile
from cartwall.reconcile.posting import post
from cartwall.scheduling.inventory import DayBoard, Placement
from cartwall.services.invoicing import (
    AlreadyInvoiced,
    InvoicingError,
    build_invoice,
    drifted,
    orders_with_air,
    raise_one,
    run_month,
)
from cartwall.services.numbering import Numbering
from support import CARD_DATE, MONDAY, a_grid

FIXED = ClassOfTime.FIXED
AUGUST = BroadcastMonth(2025, 8)
SIGNED = datetime(2025, 8, 1, 16, 30)
RAISED = date(2025, 8, 25)
HEADER = "date,time,cart,title,length,status,reference"


@pytest.fixture
def engine(tmp_path, cluster, accounts, cards, programming):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'inv.sqlite3'}")
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.cards.file(cards.in_force("KWLO", CARD_DATE))
        uow.stations.save_grid(programming["KWLO"])
        uow.commit()
    return engine


def an_order(number="25-1044", advertiser="HYVEE", agency=None, rate="60.00", lines=None):
    return Order(
        number=number,
        advertiser=advertiser,
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        agency=agency,
        lines=lines
        or (
            Line(
                number=1,
                station="KWLO",
                daypart="AMD",
                grade=FIXED,
                length=THIRTY,
                rate=Money.parse(rate),
                flight=Flight.build(MONDAY, date(2025, 8, 8), "M-F", 10),
            ),
        ),
    ).confirmed()


def a_day(uow, grid, day, spots, asrun_lines):
    """Build a day, air it, and post an as-run against it."""
    board = DayBoard.build(grid, day)
    for hour, number, order, line, cart in spots:
        board.avail(hour, number).add(
            Placement(
                order=order, line=line, advertiser="HYVEE", length=THIRTY, grade=FIXED, cart=cart
            )
        )
    logs = LogRepository(uow.session)
    logs.save(board)
    logs.approve("KWLO", day, "T. Ronan", SIGNED)
    logs.release("KWLO", day, SIGNED)
    logs.mark_aired("KWLO", day)
    rows = parse("\n".join([HEADER, *asrun_lines]) + "\n") if asrun_lines else []
    post(uow.session, reconcile(DayLog.build(grid, board), rows))


def ran(day, at, cart="C1204", status="played", reference="25-1044-1"):
    return f"{day},{at},{cart},a title,30.0,{status},{reference}"


def test_a_month_with_nothing_aired_raises_nothing(engine):
    with unit_of_work(engine) as uow:
        report = run_month(uow, AUGUST, RAISED)
        assert report.count == 0
        assert report.raised == []
        assert orders_with_air(uow, AUGUST) == []


def test_an_order_that_aired_gets_an_invoice(engine, house):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "07:10:00")])
        report = run_month(uow, AUGUST, RAISED)
        uow.commit()
    assert report.count == 1
    assert report.raised == ["25-1001"]
    with unit_of_work(engine) as uow:
        invoice = uow.session.get(InvoiceRow, "25-1001")
        assert invoice.order_number == "25-1044"
        assert invoice.spots == 1
        assert invoice.gross == Money.dollars(60)
        assert invoice.net == Money.dollars(60)
        assert invoice.raised == RAISED


def test_only_the_spots_that_aired_are_billed(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(
            uow,
            grid,
            MONDAY,
            [(7, 2, "25-1044", 1, "C1204"), (9, 2, "25-1044", 1, "C1204")],
            [
                ran("2025-08-04", "07:10:00"),
                ran("2025-08-04", "09:10:00", status="skip"),
            ],
        )
        report = run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        invoice = uow.session.get(InvoiceRow, report.raised[0])
        assert invoice.spots == 1
        assert invoice.gross == Money.dollars(60)


def test_a_day_nobody_posted_an_as_run_for_bills_nothing(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        board = DayBoard.build(grid, MONDAY)
        board.avail(7, 2).add(
            Placement(
                order="25-1044",
                line=1,
                advertiser="HYVEE",
                length=THIRTY,
                grade=FIXED,
                cart="C1204",
            )
        )
        LogRepository(uow.session).save(board)
        report = run_month(uow, AUGUST, RAISED)
    assert report.count == 0


def test_an_agency_order_is_billed_to_the_agency_net_of_commission(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order(advertiser="DEALF", agency="MOP"))
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "07:10:00")])
        run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        invoice = uow.session.get(InvoiceRow, "25-1001")
        assert invoice.payer == "MOP"
        assert invoice.commission_rate == Decimal("0.1500")
        assert invoice.gross == Money.dollars(60)
        assert invoice.commission == Money.dollars(9)
        assert invoice.net == Money.dollars(51)


def test_the_detail_carries_the_time_it_really_ran(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "07:10:04")])
        run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        detail = uow.session.get(InvoiceRow, "25-1001").details[0]
        assert detail.at.second == 4
        assert not detail.moved
        assert detail.cart == "C1204"


def test_a_spot_that_moved_is_marked_on_the_detail(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "08:22:00")])
        run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        assert uow.session.get(InvoiceRow, "25-1001").details[0].moved


def test_the_summary_rolls_up_what_the_detail_says(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(
            uow,
            grid,
            MONDAY,
            [(7, 2, "25-1044", 1, "C1204"), (9, 2, "25-1044", 1, "C1204")],
            [ran("2025-08-04", "07:10:00"), ran("2025-08-04", "09:10:00")],
        )
        run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        invoice = uow.session.get(InvoiceRow, "25-1001")
        assert len(invoice.summaries) == 1
        assert invoice.summaries[0].spots == 2
        assert invoice.summaries[0].station == "KWLO"
        assert invoice.summaries[0].gross == Money.dollars(120)


def test_running_the_month_twice_does_not_raise_a_second_invoice(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "07:10:00")])
        run_month(uow, AUGUST, RAISED)
        uow.commit()
    with unit_of_work(engine) as uow:
        again = run_month(uow, AUGUST, RAISED)
        uow.commit()
    assert again.count == 0
    assert again.skipped == [("25-1044", "already invoiced")]


def test_raising_one_order_twice_is_refused_outright(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(uow, grid, MONDAY, [(7, 2, "25-1044", 1, "C1204")], [ran("2025-08-04", "07:10:00")])
        raise_one(uow, "25-1044", AUGUST, RAISED)
        with pytest.raises(AlreadyInvoiced, match="already has an invoice") as caught:
            raise_one(uow, "25-1044", AUGUST, RAISED)
        assert caught.value.context["order"] == "25-1044"


def test_raising_an_order_that_aired_nothing_is_refused(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        with pytest.raises(InvoicingError, match="aired nothing in 2025-08"):
            raise_one(uow, "25-1044", AUGUST, RAISED)


def test_the_numbers_come_off_the_counter_without_gaps(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        for number in ("25-1044", "25-2000", "25-3000"):
            uow.orders.save(an_order(number))
        a_day(
            uow,
            grid,
            MONDAY,
            [
                (7, 2, "25-1044", 1, "C1204"),
                (9, 2, "25-2000", 1, "C1205"),
                (11, 2, "25-3000", 1, "C1206"),
            ],
            [
                ran("2025-08-04", "07:10:00", reference="25-1044-1"),
                ran("2025-08-04", "09:10:00", cart="C1205", reference="25-2000-1"),
                ran("2025-08-04", "11:10:00", cart="C1206", reference="25-3000-1"),
            ],
        )
        report = run_month(uow, AUGUST, RAISED)
        uow.commit()
    assert report.raised == ["25-1001", "25-1002", "25-1003"]
    with unit_of_work(engine) as uow:
        assert Numbering(uow.session).peek("INV", 2025) == 1003


def test_a_month_boundary_is_the_broadcast_one(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(
            an_order(
                lines=(
                    Line(
                        number=1,
                        station="KWLO",
                        daypart="AMD",
                        grade=FIXED,
                        length=THIRTY,
                        rate=Money.dollars(60),
                        flight=Flight.build(date(2025, 8, 18), date(2025, 8, 29), "M-F", 5),
                    ),
                )
            )
        )
        # 8/25 is in broadcast September, not August, even though August has 31 days.
        a_day(
            uow,
            grid,
            date(2025, 8, 25),
            [(7, 2, "25-1044", 1, "C1204")],
            [ran("2025-08-25", "07:10:00")],
        )
        august = run_month(uow, AUGUST, RAISED)
        assert august.count == 0
        september = run_month(uow, BroadcastMonth(2025, 9), date(2025, 9, 29))
        uow.commit()
    assert september.count == 1


def test_the_report_reads_as_a_sentence(engine):
    with unit_of_work(engine) as uow:
        assert str(run_month(uow, AUGUST, RAISED)) == (
            "2025-08: 0 raised, 0 already done, 0 with nothing to bill"
        )


def test_a_spot_for_a_line_that_is_no_longer_on_the_order_is_refused(engine):
    grid = a_grid("KWLO")
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        a_day(
            uow,
            grid,
            MONDAY,
            [(7, 2, "25-1044", 9, "C1204")],
            [ran("2025-08-04", "07:10:00", reference="25-1044-9")],
        )
        with pytest.raises(InvoicingError, match="aired a line 9 that is not on the order"):
            build_invoice(uow, "25-1044", AUGUST, RAISED)


def test_the_drift_test_is_symmetric():
    from cartwall.airtime import AirTime

    early = AirTime.at(7, 10)
    late = AirTime.at(7, 20)
    assert drifted(late, early)
    assert drifted(early, late)
    assert not drifted(early, AirTime.at(7, 14))
