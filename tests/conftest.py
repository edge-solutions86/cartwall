"""One real cluster, filed once, so every test argues about the same numbers.

Six signals in Waterloo and Cedar Falls. The tables below are the ones the
traffic director would have on paper, and every expected value in the suite can
be worked back to them with a pencil. That matters more than it sounds: a
fixture nobody can recompute is a fixture nobody can argue with, and a test
that fails against one teaches nothing.

The dates are 2025. Broadcast August 2025 runs 7/28 to 8/24, which is why so
many tests pick a week in early August.
"""

from decimal import Decimal

import pytest

from cartwall.domain.account import AccountBook, Advertiser, Agency
from cartwall.domain.daypart import standard_grid
from cartwall.domain.programming import Assignment, ClockGrid
from cartwall.domain.ratecard import RateCardBook
from cartwall.domain.rulebook import House
from cartwall.domain.separation import house_rules
from cartwall.domain.station import Band, Cluster, Service, Station
from support import a_card, a_grid


@pytest.fixture
def cluster() -> Cluster:
    return Cluster(
        market="Waterloo Cedar Falls",
        stations=(
            Station("KWLO", Band.FM, Decimal("99.1"), "Waterloo", Service.MUSIC),
            Station("KOEL-FM", Band.FM, Decimal("92.3"), "Oelwein", Service.MUSIC),
            Station(
                "KCRR",
                Band.FM,
                Decimal("97.7"),
                "Grundy Center",
                Service.SPORTS,
                commercial_load=18,
            ),
            Station(
                "KXEL",
                Band.AM,
                Decimal("1540"),
                "Waterloo",
                Service.NEWS_TALK,
                daytimer=True,
                commercial_load=18,
            ),
            Station(
                "KCNZ",
                Band.AM,
                Decimal("1250"),
                "Cedar Falls",
                Service.FARM,
                commercial_load=18,
            ),
            Station(
                "KWLO-LP",
                Band.TRANSLATOR,
                Decimal("103.5"),
                "Cedar Falls",
                Service.MUSIC,
                carries="KWLO",
            ),
        ),
    )


@pytest.fixture
def accounts() -> AccountBook:
    book = AccountBook()
    book.add_agency(Agency("MOP", "Mopar Media"))
    book.add_agency(Agency("CRS", "Crossroads Buying", Decimal("0.15")))
    book.add_agency(Agency("HSE", "House", Decimal("0")))
    # The dealers all carry AUTO as well as their brand, which is how a real
    # book is written: two dealers of any make separate from each other.
    book.add_advertiser(Advertiser.build("HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS"))
    book.add_advertiser(Advertiser.build("CASEY", "Caseys", "FUEL,GROCERY.CONV"))
    book.add_advertiser(Advertiser.build("DEALF", "Dahl Ford", "AUTO,BRAND.FORD", agency="MOP"))
    book.add_advertiser(
        Advertiser.build("DEALC", "Community Chevrolet", "AUTO,BRAND.CHEV", agency="MOP")
    )
    book.add_advertiser(Advertiser.build("DEALT", "Toyota of Waterloo", "AUTO,BRAND.TOYO"))
    book.add_advertiser(Advertiser.build("SLUMB", "Slumberland", "FURNITURE", agency="CRS"))
    book.add_advertiser(Advertiser.build("VGM", "VGM Group", "SERVICES.HOME"))
    book.add_advertiser(Advertiser.build("BLACK", "Black Hawk Bank", "FINANCE.BANK"))
    return book


@pytest.fixture
def programming() -> dict[str, ClockGrid]:
    grids = {
        "KWLO": a_grid("KWLO"),
        "KOEL-FM": a_grid("KOEL-FM"),
        "KCRR": a_grid("KCRR", "TLK"),
        "KXEL": a_grid("KXEL", "TLK"),
        "KCNZ": a_grid("KCNZ", "TLK"),
    }
    grids["KCNZ"].assign(Assignment.build("farm block", "M-Sa", 5, 7, "FRM"))
    grids["KCRR"].assign(Assignment.preempt("friday football", "F", 19, 23))
    return grids


@pytest.fixture
def cards() -> RateCardBook:
    book = RateCardBook()
    book.file(a_card("KWLO-25", "KWLO", 60, 32))
    book.file(a_card("KOEL-25", "KOEL-FM", 48, 26))
    book.file(a_card("KCRR-25", "KCRR", 40, 22))
    book.file(a_card("KXEL-25", "KXEL", 36, 20))
    book.file(a_card("KCNZ-25", "KCNZ", 30, 18))
    return book


@pytest.fixture
def house(cluster, accounts, cards, programming) -> House:
    calls = [station.call for station in cluster.logged]
    return House(
        cluster=cluster,
        accounts=accounts,
        cards=cards,
        dayparts={call: standard_grid() for call in calls},
        programming=programming,
        # A book per station, because a book is mutable and one station filing
        # a rule must not file it for the other four.
        separation={call: house_rules() for call in calls},
    )
