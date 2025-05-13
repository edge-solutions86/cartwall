from datetime import date

import pytest

from cartwall.airtime import Duration
from cartwall.domain.account import Advertiser, Credit
from cartwall.domain.daypart import Weekday, standard_grid
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY, Cut
from cartwall.errors import LookupFailure
from cartwall.money import Money
from cartwall.persistence.base import create_all, drop_all, engine_for, session_factory
from cartwall.persistence.repositories import (
    AccountRepository,
    OrderRepository,
    RateCardRepository,
    StationRepository,
)
from support import CARD_DATE, MONDAY, a_card, a_grid

FIXED = ClassOfTime.FIXED


@pytest.fixture
def session(tmp_path):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'repo.sqlite3'}")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as session:
        yield session
    drop_all(engine)


@pytest.fixture
def stations(session, cluster):
    repo = StationRepository(session)
    repo.save_cluster(cluster)
    session.commit()
    return repo


@pytest.fixture
def book_repo(session, stations, accounts):
    repo = AccountRepository(session)
    repo.save_book(accounts)
    session.commit()
    return repo


def a_line(number=1, station="KWLO"):
    return Line(
        number=number,
        station=station,
        daypart="AMD",
        grade=FIXED,
        length=THIRTY,
        rate=Money.dollars(60),
        flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
    )


def an_order(number="25-1044", lines=None, **kw):
    return Order(
        number=number,
        advertiser=kw.pop("advertiser", "HYVEE"),
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=lines if lines is not None else (a_line(),),
        **kw,
    )


def test_a_cluster_goes_in_and_comes_back(stations, cluster):
    back = stations.cluster()
    assert len(back) == len(cluster)
    assert back.station("KWLO").frequency == cluster.station("KWLO").frequency
    assert back.logging_station("KWLO-LP").call == "KWLO"


def test_saving_a_cluster_twice_does_not_duplicate_it(session, stations, cluster):
    stations.save_cluster(cluster)
    session.commit()
    assert len(stations.cluster()) == len(cluster)


def test_asking_for_a_signal_that_is_not_on_file(stations):
    with pytest.raises(LookupFailure, match="WHO is not on file") as caught:
        stations.station("WHO")
    assert caught.value.code == "not_found"


def test_a_daypart_grid_goes_in_and_comes_back(session, stations):
    stations.save_dayparts("KWLO", standard_grid())
    session.commit()
    back = stations.dayparts("KWLO")
    assert back.of("AMD").first_hour == 6
    assert back.is_whole_week()


def test_saving_a_daypart_grid_replaces_the_one_that_was_there(session, stations):
    stations.save_dayparts("KWLO", standard_grid())
    session.commit()
    trimmed = standard_grid()
    stations.save_dayparts(
        "KWLO", type(trimmed)(tuple(d for d in trimmed.dayparts if d.code != "OVN"))
    )
    session.commit()
    assert not stations.dayparts("KWLO").has("OVN")
    assert len(stations.dayparts("KWLO").dayparts) == 8


def test_a_programming_grid_goes_in_and_comes_back_in_order(session, stations):
    grid = a_grid("KWLO")
    stations.save_grid(grid)
    session.commit()
    back = stations.grid("KWLO", CARD_DATE)
    assert [a.label for a in back.assignments] == [a.label for a in grid.assignments]
    assert back.clock_for(Weekday.MON, 7).code == "MUS"
    assert sorted(back.clocks) == ["FRM", "MUS", "TLK"]


def test_saving_a_grid_twice_replaces_it(session, stations):
    stations.save_grid(a_grid("KWLO"))
    session.commit()
    stations.save_grid(a_grid("KWLO", "TLK"))
    session.commit()
    back = stations.grid("KWLO", CARD_DATE)
    assert len(back.assignments) == 1
    assert back.clock_for(Weekday.MON, 7).code == "TLK"


def test_asking_for_a_grid_that_was_never_filed(stations):
    with pytest.raises(LookupFailure, match="has no grid effective"):
        stations.grid("KWLO", date(2030, 1, 1))


def test_an_account_book_goes_in_and_comes_back_whole(book_repo, accounts):
    back = book_repo.book()
    assert back.advertisers == accounts.advertisers
    assert back.agencies == accounts.agencies


def test_saving_the_book_twice_does_not_duplicate_the_conflict_codes(session, book_repo, accounts):
    book_repo.save_book(accounts)
    session.commit()
    back = book_repo.book()
    assert back.advertiser("HYVEE").categories == accounts.advertiser("HYVEE").categories


def test_an_account_that_changes_credit_is_written_over(session, book_repo, accounts):
    accounts.advertisers["HYVEE"] = Advertiser.build(
        "HYVEE", "Hy-Vee", "GROCERY,FUEL.GAS", credit=Credit.HOLD
    )
    book_repo.save_book(accounts)
    session.commit()
    assert book_repo.book().advertiser("HYVEE").credit is Credit.HOLD


def test_a_cut_goes_in_and_comes_back(session, book_repo):
    cut = Cut(
        cart="C1204",
        isci="HYVE2501",
        advertiser="HYVEE",
        length=THIRTY,
        actual=Duration(301),
        first_air=MONDAY,
    )
    book_repo.file_cut(cut)
    session.commit()
    assert book_repo.cut("C1204") == cut


def test_a_redub_replaces_the_cart(session, book_repo):
    for tenths in (299, 302):
        book_repo.file_cut(
            Cut(
                cart="C1204",
                isci="HYVE2501",
                advertiser="HYVEE",
                length=THIRTY,
                actual=Duration(tenths),
                first_air=MONDAY,
            )
        )
        session.commit()
    assert book_repo.cut("C1204").actual == Duration(302)


def test_asking_for_a_cart_nobody_dubbed(book_repo):
    with pytest.raises(LookupFailure, match="cart C9999 is not in the library"):
        book_repo.cut("C9999")


def test_the_library_can_be_read_whole_or_for_one_advertiser(session, book_repo):
    for cart, advertiser in (("C1204", "HYVEE"), ("C1300", "SLUMB")):
        book_repo.file_cut(
            Cut(
                cart=cart,
                isci="HYVE2501",
                advertiser=advertiser,
                length=THIRTY,
                actual=Duration(300),
                first_air=MONDAY,
            )
        )
    session.commit()
    assert len(book_repo.library()) == 2
    assert len(book_repo.library("HYVEE")) == 1


def test_a_rate_card_goes_in_and_comes_back(session, stations):
    repo = RateCardRepository(session)
    card = a_card("KWLO-25", "KWLO", 60, 32)
    repo.file(card)
    session.commit()
    assert repo.card("KWLO", CARD_DATE) == card


def test_refiling_a_card_for_the_same_day_replaces_it(session, stations):
    repo = RateCardRepository(session)
    repo.file(a_card("KWLO-25A", "KWLO", 60, 32))
    session.commit()
    repo.file(a_card("KWLO-25B", "KWLO", 70, 36))
    session.commit()
    card = repo.card("KWLO", CARD_DATE)
    assert card.code == "KWLO-25B"
    assert card.rate("AMD", FIXED, THIRTY) == Money.dollars(70)


def test_the_card_book_comes_back_in_effective_order(session, stations):
    repo = RateCardRepository(session)
    repo.file(a_card("KWLO-25", "KWLO", 60, 32))
    repo.file(a_card("KCRR-25", "KCRR", 40, 22))
    session.commit()
    book = repo.book()
    assert [card.code for card in book.history("KWLO")] == ["KWLO-25"]
    assert book.in_force("KCRR", MONDAY).code == "KCRR-25"


def test_asking_for_a_card_that_was_never_filed(session, stations):
    with pytest.raises(LookupFailure, match="has no card effective"):
        RateCardRepository(session).card("KWLO", CARD_DATE)


def test_an_order_goes_in_and_comes_back_whole(session, stations, book_repo):
    repo = OrderRepository(session)
    order = an_order(lines=(a_line(1), a_line(2, station="KCRR")))
    repo.save(order)
    session.commit()
    assert repo.get("25-1044") == order
    assert repo.exists("25-1044")
    assert not repo.exists("25-9999")


def test_saving_an_order_again_replaces_its_lines(session, stations, book_repo):
    repo = OrderRepository(session)
    repo.save(an_order(lines=(a_line(1), a_line(2))))
    session.commit()
    repo.save(an_order(lines=(a_line(1),)))
    session.commit()
    assert len(repo.get("25-1044")) == 1


def test_asking_for_an_order_that_is_not_on_file(session):
    with pytest.raises(LookupFailure, match="order 25-9999 is not on file"):
        OrderRepository(session).get("25-9999")


def test_the_orders_of_one_advertiser_come_back_in_number_order(session, stations, book_repo):
    repo = OrderRepository(session)
    repo.save(an_order("25-2000"))
    repo.save(an_order("25-1044"))
    repo.save(an_order("25-3000", advertiser="SLUMB"))
    session.commit()
    assert [order.number for order in repo.for_advertiser("HYVEE")] == ["25-1044", "25-2000"]
    assert [order.number for order in repo.for_advertiser("SLUMB")] == ["25-3000"]


def test_the_orders_running_on_a_day_are_the_ones_with_a_flight_over_it(
    session, stations, book_repo
):
    repo = OrderRepository(session)
    away = Line(
        number=1,
        station="KWLO",
        daypart="AMD",
        grade=FIXED,
        length=THIRTY,
        rate=Money.dollars(60),
        flight=Flight.build(date(2025, 9, 1), date(2025, 9, 30), "M-F", 12),
    )
    repo.save(an_order("25-1044"))
    repo.save(an_order("25-2000", lines=(away,)))
    session.commit()
    assert [order.number for order in repo.running_on(MONDAY)] == ["25-1044"]
    assert [order.number for order in repo.running_on(date(2025, 9, 3))] == ["25-2000"]
    assert repo.running_on(date(2025, 12, 25)) == []


def test_deleting_an_order_takes_it_off_the_file(session, stations, book_repo):
    repo = OrderRepository(session)
    repo.save(an_order())
    session.commit()
    repo.delete("25-1044")
    session.commit()
    assert not repo.exists("25-1044")
    with pytest.raises(LookupFailure, match="is not on file"):
        repo.delete("25-1044")
