from decimal import Decimal

import pytest

from cartwall.domain.station import (
    Band,
    Cluster,
    Service,
    Station,
    StationError,
)


def fm(call, frequency="99.1", **kw):
    return Station(
        call=call,
        band=Band.FM,
        frequency=Decimal(frequency),
        city_of_license=kw.pop("city", "Waterloo"),
        service=kw.pop("service", Service.MUSIC),
        **kw,
    )


def am(call, frequency="1540", **kw):
    return Station(
        call=call,
        band=Band.AM,
        frequency=Decimal(frequency),
        city_of_license=kw.pop("city", "Waterloo"),
        service=kw.pop("service", Service.NEWS_TALK),
        **kw,
    )


def test_a_call_sign_is_k_or_w_and_three_or_four_letters():
    assert fm("KWLO").call == "KWLO"
    assert fm("KOEL-FM").call == "KOEL-FM"
    for bad in ("BWLO", "KW", "KWLOXX", "kwlo", "KWLO-HD2", ""):
        with pytest.raises(StationError, match="not a call sign"):
            fm(bad)


def test_fm_channels_are_odd_tenths_between_88_1_and_107_9():
    assert fm("KWLO", "88.1")
    assert fm("KWLO", "107.9")
    for bad in ("88.2", "99.2", "107.8"):
        with pytest.raises(StationError, match="tenths run odd"):
            fm("KWLO", bad)
    for bad in ("87.9", "108.1"):
        with pytest.raises(StationError, match="off the FM band"):
            fm("KWLO", bad)


def test_am_channels_are_ten_kilohertz_apart_between_540_and_1700():
    assert am("KXEL", "540")
    assert am("KXEL", "1700")
    with pytest.raises(StationError, match="10 kHz channel"):
        am("KXEL", "1545")
    with pytest.raises(StationError, match="off the AM band"):
        am("KXEL", "530")
    with pytest.raises(StationError, match="whole number of kilohertz"):
        am("KXEL", "1540.5")


def test_only_an_am_can_be_a_daytimer():
    assert am("KXEL", daytimer=True).daytimer
    with pytest.raises(StationError, match="not AM"):
        fm("KWLO", daytimer=True)


def test_a_translator_has_to_carry_something():
    with pytest.raises(StationError, match="nothing to carry"):
        Station(
            call="KWLO-LP",
            band=Band.TRANSLATOR,
            frequency=Decimal("96.5"),
            city_of_license="Cedar Falls",
            service=Service.MUSIC,
        )
    with pytest.raises(StationError, match="cannot carry itself"):
        fm("KWLO", carries="KWLO")


def test_the_commercial_load_has_to_be_minutes_in_an_hour():
    assert fm("KWLO", commercial_load=18).commercial_load == 18
    for bad in (-1, 61):
        with pytest.raises(StationError, match="is not a load"):
            fm("KWLO", commercial_load=bad)


def test_how_a_signal_reads_on_paper():
    station = fm("KOEL-FM", "92.3", city="Oelwein")
    assert station.dial == "92.3 FM"
    assert station.legal_id() == "KOEL-FM, Oelwein"
    assert str(station) == "KOEL-FM"
    assert am("KXEL", "1540").dial == "1540 AM"


@pytest.fixture
def cluster():
    return Cluster(
        market="Waterloo Cedar Falls",
        stations=(
            fm("KWLO", "99.1"),
            fm("KOEL-FM", "92.3", city="Oelwein"),
            fm("KCRR", "97.7", service=Service.SPORTS),
            am("KXEL", "1540", daytimer=True),
            am("KCNZ", "1250", service=Service.SPORTS),
            Station(
                call="KWLO-LP",
                band=Band.TRANSLATOR,
                frequency=Decimal("103.5"),
                city_of_license="Cedar Falls",
                service=Service.MUSIC,
                carries="KWLO",
            ),
        ),
    )


def test_the_cluster_finds_its_own_and_refuses_the_rest(cluster):
    assert cluster.station("KXEL").band is Band.AM
    assert cluster.has("KCRR")
    assert not cluster.has("WHO")
    assert len(cluster) == 6
    assert [station.call for station in cluster][0] == "KWLO"
    with pytest.raises(StationError, match="not one of ours"):
        cluster.station("WHO")


def test_a_call_sign_cannot_appear_twice(cluster):
    with pytest.raises(StationError, match="appears twice"):
        Cluster(market="Waterloo", stations=(fm("KWLO"), fm("KWLO", "97.7")))


def test_a_translator_has_to_carry_something_in_the_same_cluster():
    with pytest.raises(StationError, match="not in the cluster"):
        Cluster(
            market="Waterloo",
            stations=(
                Station(
                    call="KWLO-LP",
                    band=Band.TRANSLATOR,
                    frequency=Decimal("103.5"),
                    city_of_license="Cedar Falls",
                    service=Service.MUSIC,
                    carries="KWLO",
                ),
            ),
        )


def test_a_simulcast_loop_is_caught_when_the_cluster_is_built():
    with pytest.raises(StationError, match="loops back"):
        Cluster(
            market="Waterloo",
            stations=(fm("KWLO", "99.1", carries="KCRR"), fm("KCRR", "97.7", carries="KWLO")),
        )


def test_only_the_signals_with_their_own_programming_get_a_log(cluster):
    assert [station.call for station in cluster.logged] == [
        "KWLO",
        "KOEL-FM",
        "KCRR",
        "KXEL",
        "KCNZ",
    ]
    assert cluster.station("KWLO-LP").is_satellite
    assert not cluster.station("KWLO").is_satellite


def test_a_spot_bought_on_the_translator_is_scheduled_on_the_parent(cluster):
    assert cluster.logging_station("KWLO-LP").call == "KWLO"
    assert cluster.logging_station("KWLO").call == "KWLO"


def test_a_spot_on_the_parent_reaches_the_translator_too(cluster):
    reached = [station.call for station in cluster.airs_on("KWLO")]
    assert reached == ["KWLO", "KWLO-LP"]
    assert [station.call for station in cluster.airs_on("KWLO-LP")] == reached
    assert [station.call for station in cluster.airs_on("KCRR")] == ["KCRR"]


def test_a_chain_of_simulcasts_resolves_to_the_one_log():
    chain = Cluster(
        market="Waterloo",
        stations=(
            fm("KWLO", "99.1"),
            Station(
                call="KWLO-LP",
                band=Band.TRANSLATOR,
                frequency=Decimal("103.5"),
                city_of_license="Cedar Falls",
                service=Service.MUSIC,
                carries="KWLO",
            ),
            Station(
                call="KCFI-LP",
                band=Band.TRANSLATOR,
                frequency=Decimal("104.9"),
                city_of_license="Hudson",
                service=Service.MUSIC,
                carries="KWLO-LP",
            ),
        ),
    )
    assert chain.logging_station("KCFI-LP").call == "KWLO"
    assert [station.call for station in chain.airs_on("KCFI-LP")] == [
        "KWLO",
        "KWLO-LP",
        "KCFI-LP",
    ]
