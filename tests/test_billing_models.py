from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cartwall.airtime import AirTime
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money
from cartwall.persistence.base import create_all, engine_for, session_factory
from cartwall.persistence.models.billing import (
    InvoiceDetailRow,
    InvoiceRow,
    InvoiceSummaryRow,
)
from cartwall.persistence.repositories import (
    AccountRepository,
    OrderRepository,
    StationRepository,
)
from support import MEMORY, MONDAY

FIXED = ClassOfTime.FIXED


@pytest.fixture
def session(cluster, accounts):
    engine = engine_for(MEMORY)
    create_all(engine)
    with session_factory(engine)() as session:
        StationRepository(session).save_cluster(cluster)
        AccountRepository(session).save_book(accounts)
        OrderRepository(session).save(
            Order(
                number="25-1044",
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
                        rate=Money.dollars(60),
                        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
                    ),
                ),
            )
        )
        session.commit()
        yield session


def an_invoice(number="25-1001", **kw):
    return InvoiceRow(
        number=number,
        order_number=kw.pop("order", "25-1044"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        payer=kw.pop("payer", "HYVEE"),
        year=kw.pop("year", 2025),
        month=kw.pop("month", 8),
        raised=kw.pop("raised", date(2025, 8, 25)),
        spots=kw.pop("spots", 2),
        gross=kw.pop("gross", Money.dollars(120)),
        commission_rate=kw.pop("rate", Decimal("0")),
        commission=kw.pop("commission", Money(0)),
        net=kw.pop("net", Money.dollars(120)),
    )


def test_an_invoice_round_trips_with_its_money_stored_not_derived(session):
    session.add(
        an_invoice(
            rate=Decimal("0.15"), commission=Money.dollars(18), net=Money.dollars(102), payer="MOP"
        )
    )
    session.commit()
    session.expire_all()
    found = session.get(InvoiceRow, "25-1001")
    assert found.gross == Money.dollars(120)
    assert found.commission == Money.dollars(18)
    assert found.net == Money.dollars(102)
    assert found.commission_rate == Decimal("0.1500")
    assert found.payer == "MOP"
    assert not found.sent
    assert repr(found) == "<InvoiceRow 25-1001 2025-08>"


def test_the_broadcast_month_is_stored_as_a_year_and_a_month(session):
    session.add(an_invoice(year=2025, month=8))
    session.commit()
    found = session.get(InvoiceRow, "25-1001")
    assert (found.year, found.month) == (2025, 8)


def test_one_order_gets_one_invoice_a_month(session):
    session.add(an_invoice("25-1001", year=2025, month=8))
    session.commit()
    session.add(an_invoice("25-1002", year=2025, month=8))
    with pytest.raises(IntegrityError):
        session.commit()


def test_one_order_gets_an_invoice_every_month(session):
    session.add(an_invoice("25-1001", month=8))
    session.add(an_invoice("25-1002", month=9))
    session.commit()
    assert len(session.scalars(select(InvoiceRow)).all()) == 2


def test_an_invoice_cannot_name_an_order_that_is_not_on_file(session):
    session.add(an_invoice(order="25-9999"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_detail_comes_back_in_air_order(session):
    invoice = an_invoice()
    invoice.details = [
        InvoiceDetailRow(
            day=date(2025, 8, 5),
            at=AirTime.at(7, 10),
            line=1,
            length=THIRTY,
            rate=Money.dollars(60),
            cart="C1204",
        ),
        InvoiceDetailRow(
            day=MONDAY,
            at=AirTime.at(9, 38),
            line=1,
            length=THIRTY,
            rate=Money.dollars(60),
            cart="C1204",
        ),
        InvoiceDetailRow(
            day=MONDAY,
            at=AirTime.at(7, 10, 4),
            line=1,
            length=THIRTY,
            rate=Money.dollars(60),
            cart="C1204",
        ),
    ]
    session.add(invoice)
    session.commit()
    session.expire_all()
    found = session.get(InvoiceRow, "25-1001")
    assert [(row.day, row.at.hour) for row in found.details] == [
        (MONDAY, 7),
        (MONDAY, 9),
        (date(2025, 8, 5), 7),
    ]
    assert found.details[0].at == AirTime.at(7, 10, 4)


def test_a_detail_line_records_whether_the_spot_moved(session):
    invoice = an_invoice()
    invoice.details = [
        InvoiceDetailRow(
            day=MONDAY,
            at=AirTime.at(8, 22),
            line=1,
            length=SIXTY,
            rate=Money.dollars(105),
            cart="C1205",
            moved=True,
        ),
    ]
    session.add(invoice)
    session.commit()
    row = session.scalars(select(InvoiceDetailRow)).one()
    assert row.moved
    assert row.length == SIXTY
    assert row.rate == Money.dollars(105)
    assert repr(row).startswith("<InvoiceDetailRow 25-1001")


def test_the_summary_comes_back_in_line_order(session):
    invoice = an_invoice()
    invoice.summaries = [
        InvoiceSummaryRow(
            line=2,
            station="KCRR",
            daypart="MID",
            length=THIRTY,
            rate=Money.dollars(22),
            spots=4,
            gross=Money.dollars(88),
        ),
        InvoiceSummaryRow(
            line=1,
            station="KWLO",
            daypart="AMD",
            length=THIRTY,
            rate=Money.dollars(60),
            spots=2,
            gross=Money.dollars(120),
        ),
    ]
    session.add(invoice)
    session.commit()
    session.expire_all()
    found = session.get(InvoiceRow, "25-1001")
    assert [row.line for row in found.summaries] == [1, 2]
    assert found.summaries[0].gross == Money.dollars(120)
    assert repr(found.summaries[0]) == "<InvoiceSummaryRow 25-1001 line 1>"


def test_a_summary_cannot_carry_one_line_twice(session):
    invoice = an_invoice()
    invoice.summaries = [
        InvoiceSummaryRow(
            line=1,
            station="KWLO",
            daypart="AMD",
            length=THIRTY,
            rate=Money.dollars(60),
            spots=1,
            gross=Money.dollars(60),
        ),
        InvoiceSummaryRow(
            line=1,
            station="KWLO",
            daypart="MID",
            length=THIRTY,
            rate=Money.dollars(60),
            spots=1,
            gross=Money.dollars(60),
        ),
    ]
    session.add(invoice)
    with pytest.raises(IntegrityError):
        session.commit()


def test_deleting_an_invoice_takes_its_detail_and_summary_with_it(session):
    invoice = an_invoice()
    invoice.details = [
        InvoiceDetailRow(
            day=MONDAY,
            at=AirTime.at(7, 10),
            line=1,
            length=THIRTY,
            rate=Money.dollars(60),
            cart="C1204",
        ),
    ]
    invoice.summaries = [
        InvoiceSummaryRow(
            line=1,
            station="KWLO",
            daypart="AMD",
            length=THIRTY,
            rate=Money.dollars(60),
            spots=1,
            gross=Money.dollars(60),
        ),
    ]
    session.add(invoice)
    session.commit()
    session.delete(invoice)
    session.commit()
    assert session.scalars(select(InvoiceDetailRow)).all() == []
    assert session.scalars(select(InvoiceSummaryRow)).all() == []


def test_marking_an_invoice_sent_does_not_touch_the_money(session):
    session.add(an_invoice())
    session.commit()
    invoice = session.get(InvoiceRow, "25-1001")
    invoice.sent = True
    session.commit()
    session.expire_all()
    found = session.get(InvoiceRow, "25-1001")
    assert found.sent
    assert found.gross == Money.dollars(120)


def test_invoices_can_be_found_by_the_day_they_were_raised(session):
    session.add(an_invoice("25-1001", month=8, raised=date(2025, 8, 25)))
    session.add(an_invoice("25-1002", month=9, raised=date(2025, 9, 29)))
    session.commit()
    found = session.scalars(select(InvoiceRow).where(InvoiceRow.raised <= date(2025, 8, 31))).all()
    assert [row.number for row in found] == ["25-1001"]
