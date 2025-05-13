from datetime import date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cartwall.airtime import AirTime
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.domain.station import Band, Service
from cartwall.logs.approval import LogState
from cartwall.persistence.base import create_all, drop_all, engine_for, session_factory
from cartwall.persistence.models.accounts import AdvertiserRow
from cartwall.persistence.models.logs import BumpRow, LogRow, SpotRow
from cartwall.persistence.models.reference import StationRow

FIXED = ClassOfTime.FIXED
IMMEDIATE = ClassOfTime.IMMEDIATE

MONDAY = date(2025, 8, 4)
SIGNED = datetime(2025, 8, 1, 16, 30)


@pytest.fixture
def session(tmp_path):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'logs.sqlite3'}")
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
        session.add(AdvertiserRow(code="HYVEE", name="Hy-Vee"))
        session.add(AdvertiserRow(code="SLUMB", name="Slumberland"))
        session.commit()
        yield session
    drop_all(engine)


def a_spot(hour=7, number=2, position=0, at=None, order="25-1044", line=1, **kw):
    return SpotRow(
        hour=hour,
        break_number=number,
        position=position,
        at=at if at is not None else AirTime.at(hour, 10),
        order_number=order,
        line=line,
        advertiser=kw.pop("advertiser", "HYVEE"),
        length=kw.pop("length", THIRTY),
        grade=kw.pop("grade", FIXED),
        cart=kw.pop("cart", "C1204"),
        **kw,
    )


def a_log(session, day=MONDAY, spots=None):
    log = LogRow(station="KWLO", day=day)
    log.spots = spots if spots is not None else []
    session.add(log)
    return log


def test_a_log_starts_in_draft_at_revision_zero(session):
    a_log(session)
    session.commit()
    session.expire_all()
    found = session.scalars(select(LogRow)).one()
    assert found.state is LogState.DRAFT
    assert found.revision == 0
    assert found.approved_by == ""
    assert found.approved_at is None
    assert repr(found) == "<LogRow KWLO 2025-08-04 rev 0>"


def test_signing_a_log_records_the_person_and_the_moment(session):
    log = a_log(session)
    session.commit()
    log.state = LogState.APPROVED
    log.approved_by = "T. Ronan"
    log.approved_at = SIGNED
    session.commit()
    session.expire_all()
    found = session.scalars(select(LogRow)).one()
    assert found.state is LogState.APPROVED
    assert found.approved_by == "T. Ronan"
    assert found.approved_at == SIGNED


def test_one_station_gets_one_log_per_day(session):
    a_log(session)
    a_log(session)
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_station_gets_a_log_for_every_day(session):
    a_log(session, MONDAY)
    a_log(session, date(2025, 8, 5))
    session.commit()
    assert len(session.scalars(select(LogRow)).all()) == 2


def test_the_spots_come_back_in_air_order_whatever_the_query_plan_thinks(session):
    a_log(
        session,
        spots=[
            a_spot(hour=9, number=4, position=0, line=3),
            a_spot(hour=7, number=2, position=1, line=2),
            a_spot(hour=7, number=2, position=0, line=1),
            a_spot(hour=7, number=5, position=0, line=4),
        ],
    )
    session.commit()
    session.expire_all()
    found = session.scalars(select(LogRow)).one()
    assert [spot.line for spot in found.spots] == [1, 2, 4, 3]


def test_two_spots_cannot_hold_the_same_place_in_a_break(session):
    a_log(session, spots=[a_spot(position=0, line=1), a_spot(position=0, line=2)])
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_air_time_is_stored_and_not_recomputed(session):
    a_log(session, spots=[a_spot(at=AirTime.at(7, 10, 30))])
    session.commit()
    session.expire_all()
    spot = session.scalars(select(SpotRow)).one()
    assert spot.at == AirTime.at(7, 10, 30)
    assert spot.key == ("25-1044", 1)
    assert repr(spot) == "<SpotRow 25-1044-1 07:10:30>"


def test_a_spot_keeps_its_length_grade_and_cart(session):
    a_log(session, spots=[a_spot(length=SIXTY, grade=IMMEDIATE, cart="C1205", fixed=True)])
    session.commit()
    session.expire_all()
    spot = session.scalars(select(SpotRow)).one()
    assert spot.length == SIXTY
    assert spot.grade is IMMEDIATE
    assert spot.cart == "C1205"
    assert spot.fixed


def test_a_spot_cannot_name_an_advertiser_that_is_not_on_file(session):
    a_log(session, spots=[a_spot(advertiser="NOPE")])
    with pytest.raises(IntegrityError):
        session.commit()


def test_deleting_a_log_takes_its_spots_with_it(session):
    log = a_log(session, spots=[a_spot(line=1), a_spot(position=1, line=2)])
    session.commit()
    session.delete(log)
    session.commit()
    assert session.scalars(select(SpotRow)).all() == []


def test_a_bump_records_what_came_off_and_who_took_it(session):
    session.add(
        BumpRow(
            station="KWLO",
            day=MONDAY,
            hour=7,
            break_number=2,
            order_number="25-0100",
            line=3,
            advertiser="SLUMB",
            length=THIRTY,
            grade=IMMEDIATE,
            by_order="25-9000",
            by_line=1,
        )
    )
    session.commit()
    found = session.scalars(select(BumpRow)).one()
    assert found.key == ("25-0100", 3)
    assert found.by_order == "25-9000"
    assert not found.made_good
    assert repr(found) == "<BumpRow 25-0100-3 off 2025-08-04>"


def test_a_bump_is_not_a_flag_on_the_spot_it_took_off(session):
    a_log(session, spots=[a_spot()])
    session.add(
        BumpRow(
            station="KWLO",
            day=MONDAY,
            hour=7,
            break_number=2,
            order_number="25-0100",
            line=3,
            advertiser="SLUMB",
            length=THIRTY,
            grade=IMMEDIATE,
            by_order="25-1044",
            by_line=1,
        )
    )
    session.commit()
    # The log holds one spot. The bump table holds the debt, separately.
    assert len(session.scalars(select(SpotRow)).all()) == 1
    assert len(session.scalars(select(BumpRow)).all()) == 1


def test_a_bump_can_be_marked_made_good_without_touching_the_log(session):
    bump = BumpRow(
        station="KWLO",
        day=MONDAY,
        hour=7,
        break_number=2,
        order_number="25-0100",
        line=3,
        advertiser="SLUMB",
        length=THIRTY,
        grade=IMMEDIATE,
        by_order="25-9000",
        by_line=1,
    )
    session.add(bump)
    session.commit()
    bump.made_good = True
    session.commit()
    session.expire_all()
    assert session.scalars(select(BumpRow)).one().made_good


def test_one_line_can_be_bumped_more_than_once(session):
    for day in (MONDAY, date(2025, 8, 5)):
        session.add(
            BumpRow(
                station="KWLO",
                day=day,
                hour=7,
                break_number=2,
                order_number="25-0100",
                line=3,
                advertiser="SLUMB",
                length=THIRTY,
                grade=IMMEDIATE,
                by_order="25-9000",
                by_line=1,
            )
        )
    session.commit()
    owed = session.scalars(select(BumpRow).where(BumpRow.order_number == "25-0100")).all()
    assert len(owed) == 2
