from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cartwall.airtime import Duration
from cartwall.domain.order import OrderStatus
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.rotation import RotationKind
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.domain.station import Band, Service
from cartwall.money import Money
from cartwall.persistence.base import create_all, drop_all, engine_for, session_factory
from cartwall.persistence.models.accounts import AdvertiserRow, AgencyRow, CutRow
from cartwall.persistence.models.reference import StationRow
from cartwall.persistence.models.trading import LineRow, OrderRow, RotationRow

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE

MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 29)


@pytest.fixture
def session(tmp_path):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'trading.sqlite3'}")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        session.add(
            StationRow(
                call="KWLO",
                band=Band.FM,
                frequency=Decimal("99.1"),
                city_of_license="Waterloo",
                service=Service.MUSIC,
                market="WCF",
            )
        )
        session.add(AgencyRow(code="MOP", name="Mopar Media", rate=Decimal("0.15")))
        session.add(AdvertiserRow(code="HYVEE", name="Hy-Vee"))
        session.commit()
        yield session
    drop_all(engine)


def a_line(number=1, **kw):
    return LineRow(
        number=number,
        station=kw.pop("station", "KWLO"),
        daypart=kw.pop("daypart", "AMD"),
        grade=kw.pop("grade", FIXED),
        length=kw.pop("length", THIRTY),
        rate=kw.pop("rate", Money.dollars(60)),
        card_code=kw.pop("card_code", "KWLO-25"),
        first_air=kw.pop("first_air", MONDAY),
        last_air=kw.pop("last_air", FRIDAY),
        days=kw.pop("days", "M-F"),
        per_week=kw.pop("per_week", 12),
        **kw,
    )


def an_order(session, number="25-1044", lines=None, **kw):
    order = OrderRow(
        number=number,
        advertiser=kw.pop("advertiser", "HYVEE"),
        agency=kw.pop("agency", None),
        written=kw.pop("written", date(2025, 7, 21)),
        salesperson=kw.pop("salesperson", "DHOLM"),
        **kw,
    )
    order.lines = lines if lines is not None else [a_line()]
    session.add(order)
    return order


def a_cut(cart="C1204", isci="HYVE2501"):
    return CutRow(
        cart=cart,
        isci=isci,
        advertiser="HYVEE",
        length=THIRTY,
        actual=Duration(300),
        first_air=MONDAY,
    )


def test_an_order_starts_pending_and_round_trips(session):
    an_order(session)
    session.commit()
    session.expire_all()
    found = session.get(OrderRow, "25-1044")
    assert found.status is OrderStatus.PENDING
    assert found.salesperson == "DHOLM"
    assert found.campaign == ""
    assert repr(found) == "<OrderRow 25-1044>"


def test_an_order_cannot_name_an_advertiser_that_is_not_on_file(session):
    an_order(session, advertiser="NOPE")
    with pytest.raises(IntegrityError):
        session.commit()


def test_an_order_cannot_name_an_agency_that_is_not_on_file(session):
    an_order(session, agency="NOPE")
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_lines_come_back_in_line_number_order(session):
    an_order(session, lines=[a_line(3), a_line(1), a_line(2)])
    session.commit()
    session.expire_all()
    found = session.get(OrderRow, "25-1044")
    assert [line.number for line in found.lines] == [1, 2, 3]


def test_an_order_may_not_number_a_line_twice(session):
    an_order(session, lines=[a_line(1), a_line(1, daypart="MID")])
    with pytest.raises(IntegrityError):
        session.commit()


def test_two_orders_may_both_have_a_line_one(session):
    an_order(session, "25-1044")
    an_order(session, "25-1045")
    session.commit()
    assert len(session.scalars(select(LineRow)).all()) == 2


def test_the_flight_lives_on_the_line_rather_than_in_its_own_table(session):
    an_order(session, lines=[a_line(days="Sa,Su", per_week=4)])
    session.commit()
    session.expire_all()
    line = session.scalars(select(LineRow)).one()
    assert line.days == "Sa,Su"
    assert line.per_week == 4
    assert line.first_air == MONDAY
    assert line.last_air == FRIDAY


def test_the_rate_is_stored_on_the_line_with_the_card_it_came_off(session):
    an_order(session, lines=[a_line(rate=Money.dollars(60), card_code="KWLO-25")])
    session.commit()
    session.expire_all()
    line = session.scalars(select(LineRow)).one()
    assert line.rate == Money(6000)
    assert line.card_code == "KWLO-25"
    assert line.grade is FIXED
    assert line.length == THIRTY


def test_a_sixty_second_line_keeps_its_length(session):
    an_order(session, lines=[a_line(length=SIXTY, grade=NOTICE)])
    session.commit()
    session.expire_all()
    line = session.scalars(select(LineRow)).one()
    assert line.length == SIXTY
    assert line.grade is NOTICE


def test_deleting_an_order_takes_its_lines_with_it(session):
    order = an_order(session, lines=[a_line(1), a_line(2, daypart="MID")])
    session.commit()
    session.delete(order)
    session.commit()
    assert session.scalars(select(LineRow)).all() == []


def test_a_line_carries_its_rotation_in_the_order_it_was_written(session):
    session.add(a_cut("C1204"))
    session.add(a_cut("C1205", "HYVE2502"))
    session.add(a_cut("C1206", "HYVE2503"))
    line = a_line()
    line.rotation = [
        RotationRow(position=3, cart="C1206"),
        RotationRow(position=1, cart="C1204", weight=2, kind=RotationKind.WEIGHTED),
        RotationRow(position=2, cart="C1205", kind=RotationKind.WEIGHTED),
    ]
    an_order(session, lines=[line])
    session.commit()
    session.expire_all()
    found = session.scalars(select(LineRow)).one()
    assert [row.cart for row in found.rotation] == ["C1204", "C1205", "C1206"]
    assert found.rotation[0].weight == 2
    assert found.rotation[0].kind is RotationKind.WEIGHTED
    assert repr(found.rotation[0]).startswith("<RotationRow")


def test_a_rotation_cannot_carry_the_same_cart_twice(session):
    session.add(a_cut("C1204"))
    line = a_line()
    line.rotation = [
        RotationRow(position=1, cart="C1204"),
        RotationRow(position=2, cart="C1204"),
    ]
    an_order(session, lines=[line])
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_rotation_cannot_name_a_cart_nobody_dubbed(session):
    line = a_line()
    line.rotation = [RotationRow(position=1, cart="C9999")]
    an_order(session, lines=[line])
    with pytest.raises(IntegrityError):
        session.commit()


def test_deleting_a_line_takes_its_rotation_with_it(session):
    session.add(a_cut("C1204"))
    line = a_line()
    line.rotation = [RotationRow(position=1, cart="C1204")]
    order = an_order(session, lines=[line])
    session.commit()
    order.lines = []
    session.commit()
    assert session.scalars(select(RotationRow)).all() == []


def test_confirming_an_order_is_a_status_change_and_nothing_else(session):
    order = an_order(session)
    session.commit()
    order.status = OrderStatus.CONFIRMED
    session.commit()
    session.expire_all()
    found = session.get(OrderRow, "25-1044")
    assert found.status is OrderStatus.CONFIRMED
    assert len(found.lines) == 1
