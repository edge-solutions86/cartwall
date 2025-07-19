"""Every route behind the role it needs, and every write on the trail.

The role table below is the interface. A route that changes which role it needs
changes what six people can do on a Monday morning, so it is a decision that
gets made here and not in a decorator somebody skims past.
"""

import pytest
from starlette.testclient import TestClient

from cartwall.api.app import create_app
from cartwall.api.security import HEADER, KeyRing
from cartwall.config import Settings
from cartwall.domain.daypart import standard_grid
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.audit import about, on_day
from support import CARD_DATE, MEMORY

RING = (
    "eyre:reader:readerkey;dholm:seller:sellerkey;"
    "tronan:traffic:traffickey;mstone:manager:managerkey"
)

READER = {HEADER: "readerkey"}
SELLER = {HEADER: "sellerkey"}
TRAFFIC = {HEADER: "traffickey"}
MANAGER = {HEADER: "managerkey"}


@pytest.fixture
def engine(cluster, accounts, cards, programming):
    engine = engine_for(MEMORY)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_dayparts(call, standard_grid())
            uow.stations.save_grid(programming[call])
        uow.commit()
    return engine


@pytest.fixture
def client(engine):
    settings = Settings(database_url=MEMORY)
    return TestClient(create_app(settings, engine, KeyRing.parse(RING)))


def a_line():
    return {
        "number": 1,
        "station": "KWLO",
        "daypart": "AMD",
        "grade": "fixed",
        "length": ":30",
        "rate": "60.00",
        "flight": {
            "first": "2025-08-04",
            "last": "2025-08-08",
            "days": "M-F",
            "per_week": 10,
        },
    }


def an_order(number="25-1044"):
    return {
        "number": number,
        "advertiser": "HYVEE",
        "written": "2025-07-21",
        "salesperson": "DHOLM",
        "lines": [a_line()],
    }


def test_reading_an_order_needs_only_a_reader(client):
    client.post("/orders", json=an_order(), headers=SELLER)
    assert client.get("/orders/25-1044", headers=READER).status_code == 200
    assert client.get("/orders", params={"advertiser": "HYVEE"}, headers=READER).status_code == 200


def test_writing_an_order_needs_a_seller(client):
    assert client.post("/orders", json=an_order(), headers=READER).status_code == 403
    assert client.post("/orders", json=an_order(), headers=SELLER).status_code == 201


def test_confirming_and_cancelling_need_a_seller(client):
    client.post("/orders", json=an_order(), headers=SELLER)
    assert client.post("/orders/25-1044/confirm", headers=READER).status_code == 403
    assert client.post("/orders/25-1044/confirm", headers=SELLER).status_code == 200
    assert client.post("/orders/25-1044/cancel", headers=SELLER).status_code == 200


def test_building_a_week_needs_traffic(client):
    assert client.post("/logs/build/2025-08-04", headers=SELLER).status_code == 403
    assert client.post("/logs/build/2025-08-04", headers=TRAFFIC).status_code == 200


def test_reading_a_log_needs_only_a_reader(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    assert client.get("/logs/KWLO/2025-08-04", headers=READER).status_code == 200
    assert client.get("/logs/KWLO/2025-08-04/ready", headers=READER).status_code == 200


def test_signing_and_releasing_a_log_need_traffic(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    body = {"who": "T. Ronan"}
    assert (
        client.post("/logs/KWLO/2025-08-04/approve", json=body, headers=SELLER).status_code == 403
    )
    assert (
        client.post("/logs/KWLO/2025-08-04/approve", json=body, headers=TRAFFIC).status_code == 200
    )
    assert (
        client.post("/logs/KWLO/2025-08-04/release", json=body, headers=TRAFFIC).status_code == 200
    )


def test_revising_a_released_log_needs_a_manager(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    body = {"who": "T. Ronan"}
    client.post("/logs/KWLO/2025-08-04/approve", json=body, headers=TRAFFIC)
    client.post("/logs/KWLO/2025-08-04/release", json=body, headers=TRAFFIC)
    assert client.post("/logs/KWLO/2025-08-04/revise", headers=TRAFFIC).status_code == 403
    assert client.post("/logs/KWLO/2025-08-04/revise", headers=MANAGER).status_code == 200


def test_writing_an_order_lands_on_the_trail_with_a_name(client, engine):
    client.post("/orders", json=an_order(), headers=SELLER)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "order", "25-1044")
    assert [entry.action for entry in trail] == ["order.written"]
    assert trail[0].actor == "dholm"
    assert "advertiser=HYVEE" in trail[0].detail


def test_the_whole_life_of_an_order_is_on_the_trail(client, engine):
    client.post("/orders", json=an_order(), headers=SELLER)
    client.put("/orders/25-1044", json=an_order(), headers=SELLER)
    client.post("/orders/25-1044/confirm", headers=SELLER)
    client.post("/orders/25-1044/cancel", headers=SELLER)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "order", "25-1044")
    assert [entry.action for entry in trail] == [
        "order.written",
        "order.rewritten",
        "order.confirmed",
        "order.cancelled",
    ]


def test_a_refused_write_leaves_nothing_on_the_trail(client, engine):
    client.post("/orders", json=an_order(), headers=READER)
    with unit_of_work(engine) as uow:
        assert about(uow.session, "order", "25-1044") == []


def test_a_failed_write_rolls_the_trail_back_with_it(client, engine):
    body = an_order()
    body["lines"][0]["length"] = ":25"
    assert client.post("/orders", json=body, headers=SELLER).status_code == 422
    with unit_of_work(engine) as uow:
        assert about(uow.session, "order", "25-1044") == []


def test_signing_a_log_records_who_pressed_the_button_and_who_signed_it(client, engine):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"}, headers=TRAFFIC)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "log", "KWLO 2025-08-04")
    assert trail[0].action == "log.approved"
    assert trail[0].actor == "tronan"
    assert 'signed_by="T. Ronan"' in trail[0].detail


def test_a_build_is_recorded_against_the_week(client, engine):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "week", "week of 2025-08-04")
    assert trail[0].action == "log.built"
    assert "written=35" in trail[0].detail


def test_reading_writes_nothing_to_the_trail(client, engine):
    client.post("/orders", json=an_order(), headers=SELLER)
    before = len(_today(engine))
    client.get("/orders/25-1044", headers=READER)
    client.get("/orders", params={"advertiser": "HYVEE"}, headers=READER)
    assert len(_today(engine)) == before


def _today(engine):
    from datetime import date

    with unit_of_work(engine) as uow:
        return on_day(uow.session, date.today())
