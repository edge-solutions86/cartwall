import pytest
from fastapi import APIRouter
from starlette.testclient import TestClient

from cartwall.api.app import create_app
from cartwall.api.security import (
    HEADER,
    KEYS_VAR,
    Caller,
    KeyRing,
    Manager,
    NotAllowed,
    NotAuthenticated,
    Reader,
    Role,
    Seller,
    Traffic,
    digest,
)
from cartwall.config import Settings
from cartwall.persistence.base import create_all, engine_for

RING = "dholm:seller:sellerkey;tronan:traffic:traffickey;mstone:manager:managerkey"


@pytest.fixture
def app(tmp_path):
    settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'sec.sqlite3'}")
    engine = engine_for(settings.database_url)
    create_all(engine)
    app = create_app(settings, engine, KeyRing.parse(RING + ";eyre:reader:readerkey"))

    router = APIRouter()

    @router.get("/anybody")
    def anybody(caller: Caller = Reader):
        return {"who": str(caller)}

    @router.post("/sell")
    def sell(caller: Caller = Seller):
        return {"who": caller.name}

    @router.post("/traffic")
    def traffic(caller: Caller = Traffic):
        return {"who": caller.name}

    @router.post("/bill")
    def bill(caller: Caller = Manager):
        return {"who": caller.name}

    app.include_router(router)
    return app


@pytest.fixture
def client(app):
    return TestClient(app)


def as_key(key):
    return {HEADER: key}


def test_the_roles_are_a_ladder():
    assert Role.MANAGER > Role.TRAFFIC > Role.SELLER > Role.READER
    assert Role.TRAFFIC.can(Role.SELLER)
    assert Role.TRAFFIC.can(Role.TRAFFIC)
    assert not Role.SELLER.can(Role.TRAFFIC)


def test_a_role_is_named_the_way_the_office_names_it():
    assert Role.named("seller") is Role.SELLER
    assert Role.named(" TRAFFIC ") is Role.TRAFFIC
    assert Role.MANAGER.label == "manager"
    with pytest.raises(NotAuthenticated, match="is not a role"):
        Role.named("boss")


def test_a_caller_carries_a_person_as_well_as_a_role():
    caller = Caller("DHOLM", Role.SELLER)
    assert str(caller) == "DHOLM (seller)"
    caller.require(Role.READER)
    with pytest.raises(NotAllowed, match="is seller and this needs traffic") as caught:
        caller.require(Role.TRAFFIC)
    assert caught.value.context == {"actor": "DHOLM", "has": "seller", "needs": "traffic"}


def test_a_ring_reads_name_role_and_key():
    ring = KeyRing.parse(RING)
    assert len(ring) == 3
    assert ring.caller("sellerkey") == Caller("dholm", Role.SELLER)
    assert ring.caller("traffickey").role is Role.TRAFFIC


def test_a_ring_entry_that_is_not_three_parts():
    with pytest.raises(NotAuthenticated, match="is not a name, a role and a key"):
        KeyRing.parse("dholm:sellerkey")
    with pytest.raises(NotAuthenticated, match="has no name or no key"):
        KeyRing.parse("dholm:seller:")
    with pytest.raises(NotAuthenticated, match="has no name or no key"):
        KeyRing.parse(":seller:key")


def test_blank_entries_and_spacing_are_forgiven():
    ring = KeyRing.parse(" dholm : seller : sellerkey ; ; ")
    assert len(ring) == 1
    assert ring.caller("sellerkey").name == "dholm"


def test_an_empty_ring_is_empty_rather_than_permissive():
    ring = KeyRing.parse("")
    assert ring.is_empty
    with pytest.raises(NotAuthenticated):
        ring.caller("anything")


def test_the_keys_are_stored_hashed_and_never_in_the_clear():
    ring = KeyRing.parse(RING)
    assert "sellerkey" not in ring.keys
    assert digest("sellerkey") in ring.keys


def test_a_key_nobody_issued():
    with pytest.raises(NotAuthenticated, match="not one of ours"):
        KeyRing.parse(RING).caller("guess")
    with pytest.raises(NotAuthenticated, match="no X-Cartwall-Key header"):
        KeyRing.parse(RING).caller(None)


def test_a_ring_can_come_from_the_environment(monkeypatch):
    monkeypatch.setenv(KEYS_VAR, RING)
    assert len(KeyRing.from_environment()) == 3
    monkeypatch.delenv(KEYS_VAR)
    assert KeyRing.from_environment().is_empty


def test_health_is_outside_the_gate(client):
    assert client.get("/health").status_code == 200
    assert client.get("/ready").status_code == 200


def test_a_request_with_no_key_is_a_401(client):
    response = client.get("/anybody")
    assert response.status_code == 401
    assert response.json()["code"] == "not_authenticated"


def test_a_request_with_a_key_nobody_issued_is_a_401(client):
    response = client.get("/anybody", headers=as_key("guess"))
    assert response.status_code == 401
    assert "not one of ours" in response.json()["message"]


def test_a_reader_can_read(client):
    body = client.get("/anybody", headers=as_key("readerkey")).json()
    assert body["who"] == "eyre (reader)"


def test_a_reader_cannot_write(client):
    response = client.post("/sell", headers=as_key("readerkey"))
    assert response.status_code == 403
    assert response.json()["code"] == "not_allowed"
    assert response.json()["context"] == {
        "actor": "eyre",
        "has": "reader",
        "needs": "seller",
    }


def test_a_seller_can_sell_and_not_touch_a_log(client):
    assert client.post("/sell", headers=as_key("sellerkey")).json() == {"who": "dholm"}
    assert client.post("/traffic", headers=as_key("sellerkey")).status_code == 403


def test_traffic_can_do_everything_a_seller_can(client):
    assert client.post("/sell", headers=as_key("traffickey")).status_code == 200
    assert client.post("/traffic", headers=as_key("traffickey")).status_code == 200
    assert client.post("/bill", headers=as_key("traffickey")).status_code == 403


def test_a_manager_can_do_the_lot(client):
    for path in ("/sell", "/traffic", "/bill"):
        assert client.post(path, headers=as_key("managerkey")).status_code == 200


def test_an_application_with_no_keys_lets_nobody_in(tmp_path):
    settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'none.sqlite3'}")
    engine = engine_for(settings.database_url)
    create_all(engine)
    app = create_app(settings, engine, KeyRing.parse(""))

    router = APIRouter()

    @router.get("/anybody")
    def anybody(caller: Caller = Reader):
        return {"who": caller.name}

    app.include_router(router)
    client = TestClient(app)
    response = client.get("/anybody", headers=as_key("anything"))
    assert response.status_code == 401
    assert "no keys are configured" in response.json()["message"]
    assert response.json()["context"]["hint"] == f"set {KEYS_VAR}"
