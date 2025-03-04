from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cartwall.airtime import AirTime, Duration
from cartwall.domain.clock import BreakKind
from cartwall.domain.programming import Source
from cartwall.domain.station import Band, Service
from cartwall.money import Money
from cartwall.persistence.base import (
    Base,
    Cents,
    ClockTenths,
    EnumValue,
    Tenths,
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from cartwall.persistence.models.reference import (
    AssignmentRow,
    BreakRow,
    ClockRow,
    DaypartRow,
    StationRow,
)

JANUARY = date(2025, 1, 1)


@pytest.fixture
def session(tmp_path):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'reference.sqlite3'}")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        yield session
    drop_all(engine)


def a_station(call="KWLO", **kw):
    return StationRow(
        call=call,
        band=kw.pop("band", Band.FM),
        frequency=kw.pop("frequency", Decimal("99.1")),
        city_of_license=kw.pop("city", "Waterloo"),
        service=kw.pop("service", Service.MUSIC),
        market=kw.pop("market", "Waterloo Cedar Falls"),
        **kw,
    )


def test_every_constraint_carries_a_name_we_chose():
    names = {
        constraint.name
        for table in Base.metadata.tables.values()
        for constraint in table.constraints
        if constraint.name is not None
    }
    assert "pk_station" in names
    assert "uq_daypart_station_code" in names
    assert all(not name.startswith("_unnamed_") for name in names)


def test_money_goes_in_as_cents_and_comes_back_as_money():
    column = Cents()
    assert column.process_bind_param(Money(12550), None) == 12550
    assert column.process_result_value(12550, None) == Money(12550)
    assert column.process_bind_param(None, None) is None
    assert column.process_result_value(None, None) is None


def test_lengths_go_in_as_tenths():
    column = Tenths()
    assert column.process_bind_param(Duration(305), None) == 305
    assert column.process_result_value(305, None) == Duration(305)
    assert column.process_bind_param(None, None) is None


def test_air_times_go_in_as_tenths_from_midnight_with_no_timezone():
    column = ClockTenths()
    assert column.process_bind_param(AirTime.at(7, 10), None) == 258000
    assert column.process_result_value(258000, None) == AirTime.at(7, 10)
    assert column.process_result_value(None, None) is None


def test_an_enum_is_stored_as_its_short_string():
    column = EnumValue(BreakKind)
    assert column.process_bind_param(BreakKind.LEGAL_ID, None) == "legal id"
    assert column.process_result_value("legal id", None) is BreakKind.LEGAL_ID
    assert column.process_bind_param(None, None) is None


def test_a_station_round_trips_with_its_enumerations_intact(session):
    session.add(a_station(daytimer=False, commercial_load=18))
    session.commit()
    found = session.get(StationRow, "KWLO")
    assert found.band is Band.FM
    assert found.service is Service.MUSIC
    assert found.commercial_load == 18
    assert repr(found) == "<StationRow KWLO>"


def test_a_translator_points_at_the_station_it_carries(session):
    session.add(a_station("KWLO"))
    session.add(a_station("KWLO-LP", band=Band.TRANSLATOR, carries="KWLO"))
    session.commit()
    found = session.get(StationRow, "KWLO-LP")
    assert found.carries == "KWLO"


def test_a_translator_cannot_carry_a_station_that_is_not_there(session):
    session.add(a_station("KWLO-LP", band=Band.TRANSLATOR, carries="NOPE"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_sqlite_is_told_to_enforce_foreign_keys(session):
    session.add(DaypartRow(station="NOPE", code="AMD", name="drive", days="M-F",
                           first_hour=6, last_hour=10))
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_station_may_not_file_the_same_daypart_code_twice(session):
    session.add(a_station())
    session.add(DaypartRow(station="KWLO", code="AMD", name="drive", days="M-F",
                           first_hour=6, last_hour=10))
    session.commit()
    session.add(DaypartRow(station="KWLO", code="AMD", name="again", days="M-F",
                           first_hour=6, last_hour=10))
    with pytest.raises(IntegrityError):
        session.commit()


def test_two_stations_may_use_the_same_daypart_code(session):
    session.add(a_station("KWLO"))
    session.add(a_station("KCRR", frequency=Decimal("97.7")))
    session.add(DaypartRow(station="KWLO", code="AMD", name="drive", days="M-F",
                           first_hour=6, last_hour=10))
    session.add(DaypartRow(station="KCRR", code="AMD", name="drive", days="M-F",
                           first_hour=6, last_hour=10))
    session.commit()
    assert len(session.scalars(select(DaypartRow)).all()) == 2


def test_a_clock_carries_its_breaks_in_number_order(session):
    session.add(a_station())
    clock = ClockRow(station="KWLO", code="MUS", name="music hour", effective=JANUARY)
    clock.breaks = [
        BreakRow(number=3, kind=BreakKind.LOCAL, position=Duration.of_seconds(1320),
                 length=Duration.of_seconds(180), units=6),
        BreakRow(number=1, kind=BreakKind.LEGAL_ID, position=Duration(0),
                 length=Duration.of_seconds(10), units=1, paid=False),
        BreakRow(number=2, kind=BreakKind.LOCAL, position=Duration.of_seconds(600),
                 length=Duration.of_seconds(180), units=6),
    ]
    session.add(clock)
    session.commit()
    session.expire_all()
    found = session.scalars(select(ClockRow)).one()
    assert [brk.number for brk in found.breaks] == [1, 2, 3]
    assert found.breaks[0].kind is BreakKind.LEGAL_ID
    assert found.breaks[1].position == Duration.of_seconds(600)


def test_a_clock_may_not_number_a_break_twice(session):
    session.add(a_station())
    clock = ClockRow(station="KWLO", code="MUS", name="music hour", effective=JANUARY)
    clock.breaks = [
        BreakRow(number=2, kind=BreakKind.LOCAL, position=Duration(0),
                 length=Duration.of_seconds(180), units=6),
        BreakRow(number=2, kind=BreakKind.LOCAL, position=Duration.of_seconds(600),
                 length=Duration.of_seconds(180), units=6),
    ]
    session.add(clock)
    with pytest.raises(IntegrityError):
        session.commit()


def test_the_same_clock_code_may_be_refiled_for_a_later_date(session):
    session.add(a_station())
    session.add(ClockRow(station="KWLO", code="MUS", name="music", effective=JANUARY))
    session.add(ClockRow(station="KWLO", code="MUS", name="music", effective=date(2025, 7, 1)))
    session.commit()
    assert len(session.scalars(select(ClockRow)).all()) == 2


def test_the_same_clock_code_may_not_be_filed_twice_for_one_date(session):
    session.add(a_station())
    session.add(ClockRow(station="KWLO", code="MUS", name="music", effective=JANUARY))
    session.add(ClockRow(station="KWLO", code="MUS", name="again", effective=JANUARY))
    with pytest.raises(IntegrityError):
        session.commit()


def test_deleting_a_clock_takes_its_breaks_with_it(session):
    session.add(a_station())
    clock = ClockRow(station="KWLO", code="MUS", name="music", effective=JANUARY)
    clock.breaks = [
        BreakRow(number=2, kind=BreakKind.LOCAL, position=Duration(0),
                 length=Duration.of_seconds(180), units=6),
    ]
    session.add(clock)
    session.commit()
    session.delete(clock)
    session.commit()
    assert session.scalars(select(BreakRow)).all() == []


def test_assignments_keep_the_order_they_were_filed_in(session):
    session.add(a_station())
    session.add(AssignmentRow(station="KWLO", effective=JANUARY, ordinal=1, label="all week",
                              days="M-Su", first_hour=0, last_hour=24, clock_code="MUS"))
    session.add(AssignmentRow(station="KWLO", effective=JANUARY, ordinal=2, label="football",
                              days="F", first_hour=19, last_hour=23, source=Source.PREEMPTED))
    session.commit()
    rows = session.scalars(
        select(AssignmentRow).order_by(AssignmentRow.ordinal)
    ).all()
    assert [row.label for row in rows] == ["all week", "football"]
    assert rows[1].source is Source.PREEMPTED
    assert rows[1].clock_code == ""


def test_two_assignments_may_not_claim_the_same_place_in_the_stack(session):
    session.add(a_station())
    for label in ("first", "second"):
        session.add(AssignmentRow(station="KWLO", effective=JANUARY, ordinal=1, label=label,
                                  days="M-Su", first_hour=0, last_hour=24, clock_code="MUS"))
    with pytest.raises(IntegrityError):
        session.commit()
