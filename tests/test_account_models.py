from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from cartwall.airtime import Duration
from cartwall.domain.account import Credit
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.domain.station import Band, Service
from cartwall.money import Money
from cartwall.persistence.base import (
    Seconds,
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from cartwall.persistence.models.accounts import (
    AdvertiserRow,
    AgencyRow,
    CategoryRow,
    CutRow,
    QuoteRow,
    RateCardRow,
)
from cartwall.persistence.models.reference import StationRow

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE

JANUARY = date(2025, 1, 1)


@pytest.fixture
def session(tmp_path):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'accounts.sqlite3'}")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        yield session
    drop_all(engine)


def an_advertiser(code="HYVEE", name="Hy-Vee", **kw):
    return AdvertiserRow(code=code, name=name, **kw)


def test_a_length_goes_in_as_seconds_and_comes_back_as_a_length():
    column = Seconds()
    assert column.process_bind_param(THIRTY, None) == 30
    assert column.process_result_value(60, None) == SIXTY
    assert column.process_bind_param(None, None) is None
    assert column.process_result_value(None, None) is None


def test_an_agency_keeps_its_commission_as_an_exact_decimal(session):
    session.add(AgencyRow(code="MOP", name="Mopar Media", rate=Decimal("0.1500")))
    session.commit()
    found = session.get(AgencyRow, "MOP")
    assert found.rate == Decimal("0.1500")
    assert not found.advertiser_liable
    assert repr(found) == "<AgencyRow MOP>"


def test_a_direct_advertiser_has_no_agency(session):
    session.add(an_advertiser())
    session.commit()
    found = session.get(AdvertiserRow, "HYVEE")
    assert found.agency is None
    assert found.credit is Credit.OPEN


def test_an_advertiser_cannot_name_an_agency_that_is_not_there(session):
    session.add(an_advertiser(agency="NOPE"))
    with pytest.raises(IntegrityError):
        session.commit()


def test_credit_status_round_trips_as_its_short_string(session):
    session.add(an_advertiser(credit=Credit.HOLD))
    session.commit()
    session.expire_all()
    assert session.get(AdvertiserRow, "HYVEE").credit is Credit.HOLD


def test_a_chain_store_points_at_its_parent(session):
    session.add(an_advertiser("HYVEE"))
    session.add(an_advertiser("HYVEE1", "Hy-Vee Kimball", parent="HYVEE"))
    session.commit()
    assert session.get(AdvertiserRow, "HYVEE1").parent == "HYVEE"


def test_conflict_codes_are_rows_so_a_root_can_be_queried(session):
    advertiser = an_advertiser()
    advertiser.categories = [
        CategoryRow(path="GROCERY", root="GROCERY"),
        CategoryRow(path="FUEL.GAS", root="FUEL"),
    ]
    session.add(advertiser)
    session.add(an_advertiser("CASEY", "Caseys"))
    session.flush()
    session.add(CategoryRow(advertiser="CASEY", path="FUEL", root="FUEL"))
    session.commit()

    on_fuel = session.scalars(
        select(CategoryRow.advertiser).where(CategoryRow.root == "FUEL")
    ).all()
    assert sorted(on_fuel) == ["CASEY", "HYVEE"]


def test_the_codes_come_back_in_a_stable_order(session):
    advertiser = an_advertiser()
    advertiser.categories = [
        CategoryRow(path="PHARM", root="PHARM"),
        CategoryRow(path="FUEL.GAS", root="FUEL"),
        CategoryRow(path="GROCERY", root="GROCERY"),
    ]
    session.add(advertiser)
    session.commit()
    session.expire_all()
    found = session.get(AdvertiserRow, "HYVEE")
    assert [row.path for row in found.categories] == ["FUEL.GAS", "GROCERY", "PHARM"]


def test_one_advertiser_cannot_carry_the_same_code_twice(session):
    advertiser = an_advertiser()
    advertiser.categories = [
        CategoryRow(path="GROCERY", root="GROCERY"),
        CategoryRow(path="GROCERY", root="GROCERY"),
    ]
    session.add(advertiser)
    with pytest.raises(IntegrityError):
        session.commit()


def test_closing_an_account_takes_its_codes_and_its_copy_with_it(session):
    advertiser = an_advertiser()
    advertiser.categories = [CategoryRow(path="GROCERY", root="GROCERY")]
    advertiser.cuts = [
        CutRow(cart="C1204", isci="HYVE2501", length=THIRTY, actual=Duration(299),
               first_air=JANUARY)
    ]
    session.add(advertiser)
    session.commit()
    session.delete(advertiser)
    session.commit()
    assert session.scalars(select(CategoryRow)).all() == []
    assert session.scalars(select(CutRow)).all() == []


def test_a_cut_keeps_its_sold_length_and_its_running_time_apart(session):
    advertiser = an_advertiser()
    advertiser.cuts = [
        CutRow(cart="C1204", isci="HYVE2501", length=THIRTY, actual=Duration(301),
               first_air=JANUARY)
    ]
    session.add(advertiser)
    session.commit()
    session.expire_all()
    found = session.get(CutRow, "C1204")
    assert found.length == THIRTY
    assert found.actual == Duration(301)
    assert found.actual != found.length.air
    assert repr(found) == "<CutRow C1204>"


def test_a_killed_cut_keeps_the_date_it_stopped(session):
    advertiser = an_advertiser()
    advertiser.cuts = [
        CutRow(cart="C1204", isci="HYVE2501", length=THIRTY, actual=Duration(300),
               first_air=JANUARY, last_air=date(2025, 2, 28), killed=True)
    ]
    session.add(advertiser)
    session.commit()
    found = session.get(CutRow, "C1204")
    assert found.killed
    assert found.last_air == date(2025, 2, 28)


def test_the_copy_comes_back_in_cart_order(session):
    advertiser = an_advertiser()
    advertiser.cuts = [
        CutRow(cart="C1300", isci="HYVE2503", length=THIRTY, actual=Duration(300),
               first_air=JANUARY),
        CutRow(cart="C1204", isci="HYVE2501", length=THIRTY, actual=Duration(300),
               first_air=JANUARY),
    ]
    session.add(advertiser)
    session.commit()
    session.expire_all()
    assert [cut.cart for cut in session.get(AdvertiserRow, "HYVEE").cuts] == ["C1204", "C1300"]


def a_station(session):
    session.add(
        StationRow(call="KWLO", band=Band.FM, frequency=Decimal("99.1"),
                   city_of_license="Waterloo", service=Service.MUSIC, market="WCF")
    )


def test_a_card_quotes_a_thirty_and_stores_it_in_cents(session):
    a_station(session)
    card = RateCardRow(code="KWLO-25", station="KWLO", effective=JANUARY)
    card.quotes = [
        QuoteRow(daypart="AMD", grade=FIXED, thirty=Money.dollars(60)),
        QuoteRow(daypart="AMD", grade=NOTICE, thirty=Money.dollars(48)),
    ]
    session.add(card)
    session.commit()
    session.expire_all()
    found = session.scalars(select(RateCardRow)).one()
    assert [quote.thirty for quote in found.quotes] == [Money(6000), Money(4800)]
    assert found.quotes[0].grade is FIXED


def test_a_card_may_not_quote_one_cell_twice(session):
    a_station(session)
    card = RateCardRow(code="KWLO-25", station="KWLO", effective=JANUARY)
    card.quotes = [
        QuoteRow(daypart="AMD", grade=FIXED, thirty=Money.dollars(60)),
        QuoteRow(daypart="AMD", grade=FIXED, thirty=Money.dollars(55)),
    ]
    session.add(card)
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_station_may_not_have_two_cards_effective_the_same_day(session):
    a_station(session)
    session.add(RateCardRow(code="KWLO-25A", station="KWLO", effective=JANUARY))
    session.add(RateCardRow(code="KWLO-25B", station="KWLO", effective=JANUARY))
    with pytest.raises(IntegrityError):
        session.commit()


def test_a_station_may_file_a_second_card_for_a_later_day(session):
    a_station(session)
    session.add(RateCardRow(code="KWLO-25A", station="KWLO", effective=JANUARY))
    session.add(RateCardRow(code="KWLO-25B", station="KWLO", effective=date(2025, 7, 1)))
    session.commit()
    assert len(session.scalars(select(RateCardRow)).all()) == 2


def test_replacing_a_card_takes_its_quotes_with_it(session):
    a_station(session)
    card = RateCardRow(code="KWLO-25", station="KWLO", effective=JANUARY)
    card.quotes = [QuoteRow(daypart="AMD", grade=FIXED, thirty=Money.dollars(60))]
    session.add(card)
    session.commit()
    session.delete(card)
    session.commit()
    assert session.scalars(select(QuoteRow)).all() == []
