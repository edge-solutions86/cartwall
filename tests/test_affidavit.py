from datetime import date

import pytest

from cartwall.domain.spot import SIXTY, THIRTY
from cartwall.money import Money
from cartwall.reports.affidavit import (
    Affidavit,
    AffidavitError,
    Ran,
    by_station,
    for_period,
    render,
)

MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 8)
SWORN = date(2025, 8, 11)


def ran(day=MONDAY, at="7:10:04a", station="KWLO", length=THIRTY, cart="C1204", rate="60.00"):
    return Ran(day=day, at=at, station=station, length=length, cart=cart, rate=Money.parse(rate))


def an_affidavit(spots=None, first=MONDAY, last=FRIDAY, **kw):
    return Affidavit(
        order=kw.pop("order", "25-1044"),
        advertiser=kw.pop("advertiser", "HYVEE"),
        first=first,
        last=last,
        spots=spots if spots is not None else [ran()],
        **kw,
    )


def test_a_spot_with_no_time_cannot_be_sworn_to():
    with pytest.raises(AffidavitError, match="has no time and cannot be sworn to"):
        ran(at="")


def test_a_spot_with_no_station_cannot_be_sworn_to():
    with pytest.raises(AffidavitError, match="names no station"):
        ran(station="")


def test_an_affidavit_that_runs_backwards_is_refused():
    with pytest.raises(AffidavitError, match="runs backwards"):
        an_affidavit(first=FRIDAY, last=MONDAY)


def test_an_affidavit_adds_up_what_ran():
    statement = an_affidavit([ran(), ran(day=FRIDAY, rate="60.00")])
    assert statement.count == 2
    assert statement.value == Money.dollars(120)
    assert statement.stations == ["KWLO"]


def test_an_affidavit_lists_the_stations_once_each_in_order():
    statement = an_affidavit([ran(station="KWLO"), ran(station="KCRR"), ran(station="KWLO")])
    assert statement.stations == ["KCRR", "KWLO"]


def test_an_affidavit_with_no_spots_cannot_be_sworn_to():
    statement = an_affidavit([])
    assert "any spots to swear to" in statement.missing()
    assert not statement.is_ready


def test_an_affidavit_needs_a_person_and_a_date():
    statement = an_affidavit()
    assert "who is swearing to it" in statement.missing()
    assert "when it was sworn" in statement.missing()
    statement.swear("T. Ronan", SWORN)
    assert statement.is_ready


def test_swearing_needs_somebody():
    with pytest.raises(AffidavitError, match="somebody has to swear to it"):
        an_affidavit().swear("", SWORN)


def test_an_unsworn_affidavit_is_refused_rather_than_rendered_blank():
    with pytest.raises(AffidavitError, match="missing who is swearing to it"):
        render(an_affidavit())


def test_a_sworn_affidavit_carries_everything_the_agency_asked_for():
    statement = an_affidavit([ran(), ran(day=FRIDAY, at="3:22:00p")])
    statement.swear("T. Ronan", SWORN, notary="M. Wills, Black Hawk County")
    text = render(statement)
    assert "AFFIDAVIT OF PERFORMANCE" in text
    assert "ORDER        25-1044" in text
    assert "ADVERTISER   HYVEE" in text
    assert "PERIOD       08/04/2025 to 08/08/2025" in text
    assert "STATIONS     KWLO" in text
    assert "08/04/2025    7:10:04a  KWLO     :30  C1204" in text
    assert "TOTAL        2 spots, $120.00" in text
    assert "SWORN BY     T. Ronan" in text
    assert "ON           08/11/2025" in text
    assert "NOTARY       M. Wills, Black Hawk County" in text


def test_an_affidavit_without_a_notary_leaves_the_line_out():
    statement = an_affidavit()
    statement.swear("T. Ronan", SWORN)
    assert "NOTARY" not in render(statement)


def test_the_spots_render_in_air_order_however_they_were_handed_in():
    statement = an_affidavit([ran(day=FRIDAY, at="3:22:00p"), ran(day=MONDAY, at="7:10:04a")])
    statement.swear("T. Ronan", SWORN)
    text = render(statement)
    assert text.index("08/04/2025") < text.index("08/08/2025")


def test_a_spot_that_ran_outside_the_period_stops_the_document():
    statement = an_affidavit([ran(), ran(day=date(2025, 8, 20))])
    statement.swear("T. Ronan", SWORN)
    assert len(statement.outside()) == 1
    with pytest.raises(AffidavitError, match="ran outside") as caught:
        render(statement)
    assert caught.value.context["first"] == "2025-08-20"


def test_building_for_a_period_keeps_only_what_ran_inside_it():
    statement = for_period(
        "25-1044",
        "HYVEE",
        MONDAY,
        FRIDAY,
        [ran(day=MONDAY), ran(day=date(2025, 8, 20)), ran(day=FRIDAY)],
    )
    assert statement.count == 2
    assert statement.outside() == []
    assert [spot.day for spot in statement.spots] == [MONDAY, FRIDAY]


def test_a_client_who_bought_several_stations_gets_them_broken_out():
    statement = an_affidavit(
        [
            ran(station="KWLO", rate="60.00"),
            ran(station="KWLO", rate="60.00"),
            ran(station="KCRR", length=SIXTY, rate="40.00"),
        ]
    )
    tally = by_station(statement)
    assert list(tally) == ["KCRR", "KWLO"]
    assert tally["KWLO"] == (2, Money.dollars(120))
    assert tally["KCRR"] == (1, Money.dollars(40))


def test_an_affidavit_reads_as_a_sentence():
    assert str(an_affidavit()) == "25-1044 HYVEE 08/04-08/08: 1 spots $60.00"
