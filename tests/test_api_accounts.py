import pytest
from starlette.testclient import TestClient

from cartwall.api.app import create_app
from cartwall.api.security import HEADER, KeyRing
from cartwall.config import Settings
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.audit import about

RING = (
    "eyre:reader:readerkey;dholm:seller:sellerkey;"
    "tronan:traffic:traffickey;mstone:manager:managerkey"
)
READER = {HEADER: "readerkey"}
SELLER = {HEADER: "sellerkey"}
TRAFFIC = {HEADER: "traffickey"}
MANAGER = {HEADER: "managerkey"}


@pytest.fixture
def engine(tmp_path, cluster, accounts):
    engine = engine_for(f"sqlite+pysqlite:///{tmp_path / 'acct.sqlite3'}")
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.commit()
    return engine


@pytest.fixture
def client(engine, tmp_path):
    settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'acct.sqlite3'}")
    return TestClient(create_app(settings, engine, KeyRing.parse(RING)))


def test_an_account_reads_back_with_its_codes_sorted(client):
    body = client.get("/accounts/HYVEE", headers=READER).json()
    assert body["name"] == "Hy-Vee"
    assert body["categories"] == ["FUEL.GAS", "GROCERY"]
    assert body["agency"] is None
    assert body["credit"] == "open"


def test_direct_business_reads_back_with_no_commission(client):
    body = client.get("/accounts/HYVEE", headers=READER).json()
    assert body["commission_rate"] == "0"
    assert body["payer"] == "HYVEE"


def test_an_agency_account_reads_back_with_the_agency_as_payer(client):
    body = client.get("/accounts/DEALF", headers=READER).json()
    assert body["agency"] == "MOP"
    assert body["commission_rate"] == "0.15"
    assert body["payer"] == "MOP"


def test_an_account_that_is_not_on_file(client):
    response = client.get("/accounts/NOBODY", headers=READER)
    assert response.status_code == 404
    assert response.json()["code"] == "no_such_account"
    assert response.json()["context"]["code"] == "NOBODY"


def test_the_agencies_come_back_in_code_order(client):
    body = client.get("/accounts", headers=READER).json()
    assert [row["code"] for row in body] == ["CRS", "HSE", "MOP"]
    assert body[2]["rate"] == "0.15"
    assert body[1]["rate"] == "0"


def test_opening_an_account_needs_a_manager(client):
    body = {"code": "NEWCO", "name": "New Company", "categories": ["FURNITURE"]}
    assert client.post("/accounts", json=body, headers=SELLER).status_code == 403
    assert client.post("/accounts", json=body, headers=MANAGER).status_code == 201


def test_an_opened_account_reads_back(client):
    client.post(
        "/accounts",
        json={"code": "NEWCO", "name": "New Company", "categories": ["AUTO", "BRAND.KIA"]},
        headers=MANAGER,
    )
    body = client.get("/accounts/NEWCO", headers=READER).json()
    assert body["categories"] == ["AUTO", "BRAND.KIA"]


def test_the_same_code_cannot_be_opened_twice(client):
    body = {"code": "HYVEE", "name": "Hy-Vee again"}
    response = client.post("/accounts", json=body, headers=MANAGER)
    assert response.status_code == 409
    assert "already on file" in response.json()["message"]


def test_a_code_that_is_not_a_code_is_refused_by_the_domain(client):
    response = client.post("/accounts", json={"code": "lower", "name": "x"}, headers=MANAGER)
    assert response.status_code == 422
    assert response.json()["code"] == "bad_account"


def test_opening_an_account_lands_on_the_trail(client, engine):
    client.post("/accounts", json={"code": "NEWCO", "name": "New Company"}, headers=MANAGER)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "advertiser", "NEWCO")
    assert trail[0].action == "advertiser.opened"
    assert trail[0].actor == "mstone"


def test_the_conflicts_of_a_dealer_are_the_other_dealers(client):
    body = client.get("/accounts/DEALF/conflicts", headers=READER).json()
    codes = [row["code"] for row in body]
    assert "DEALC" in codes
    assert "DEALT" in codes
    assert "HYVEE" not in codes


def test_a_conflict_says_which_code_it_runs_through(client):
    body = client.get("/accounts/DEALF/conflicts", headers=READER).json()
    found = {row["code"]: row for row in body}
    assert found["DEALC"]["through"] == ["AUTO"]
    assert found["DEALC"]["name"] == "Community Chevrolet"


def test_the_grocery_conflicts_run_through_two_codes(client):
    body = client.get("/accounts/HYVEE/conflicts", headers=READER).json()
    found = {row["code"]: row for row in body}
    assert sorted(found["CASEY"]["through"]) == ["FUEL", "GROCERY"]


def test_an_account_with_no_codes_conflicts_with_nobody(client):
    client.post("/accounts", json={"code": "NEWCO", "name": "New Company"}, headers=MANAGER)
    assert client.get("/accounts/NEWCO/conflicts", headers=READER).json() == []


def test_putting_an_account_on_hold_needs_a_manager(client):
    hold = {"standing": "hold"}
    assert client.put("/accounts/HYVEE/credit", params=hold, headers=TRAFFIC).status_code == 403
    response = client.put("/accounts/HYVEE/credit", params=hold, headers=MANAGER)
    assert response.status_code == 200
    assert response.json()["credit"] == "hold"


def test_a_hold_sticks_and_keeps_the_rest_of_the_account(client):
    client.put("/accounts/HYVEE/credit", params={"standing": "hold"}, headers=MANAGER)
    body = client.get("/accounts/HYVEE", headers=READER).json()
    assert body["credit"] == "hold"
    assert body["categories"] == ["FUEL.GAS", "GROCERY"]


def test_a_hold_is_recorded_with_what_it_was_before(client, engine):
    client.put("/accounts/HYVEE/credit", params={"standing": "hold"}, headers=MANAGER)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "advertiser", "HYVEE")
    assert trail[-1].action == "advertiser.credit"
    assert "was=open" in trail[-1].detail
    assert "now=hold" in trail[-1].detail


def test_a_standing_that_is_not_one_is_refused(client):
    response = client.put("/accounts/HYVEE/credit", params={"standing": "maybe"}, headers=MANAGER)
    assert response.status_code == 422


def test_filing_copy_needs_traffic(client):
    body = {
        "cart": "C1204",
        "isci": "HYVE2501",
        "advertiser": "HYVEE",
        "length": ":30",
        "actual_tenths": 299,
        "first_air": "2025-08-04",
    }
    assert client.post("/accounts/copy", json=body, headers=SELLER).status_code == 403
    response = client.post("/accounts/copy", json=body, headers=TRAFFIC)
    assert response.status_code == 201
    assert response.json()["actual_tenths"] == 299


def test_copy_that_runs_too_long_is_refused_by_the_domain(client):
    body = {
        "cart": "C1204",
        "isci": "HYVE2501",
        "advertiser": "HYVEE",
        "length": ":30",
        "actual_tenths": 320,
        "first_air": "2025-08-04",
    }
    response = client.post("/accounts/copy", json=body, headers=TRAFFIC)
    assert response.status_code == 422
    assert response.json()["code"] == "bad_spot"


def test_the_copy_of_one_advertiser_comes_back_in_cart_order(client):
    for cart, isci in (("C1300", "HYVE2503"), ("C1204", "HYVE2501")):
        client.post(
            "/accounts/copy",
            json={
                "cart": cart,
                "isci": isci,
                "advertiser": "HYVEE",
                "length": ":30",
                "actual_tenths": 300,
                "first_air": "2025-08-04",
            },
            headers=TRAFFIC,
        )
    body = client.get("/accounts/HYVEE/copy", headers=READER).json()
    assert [row["cart"] for row in body] == ["C1204", "C1300"]
    assert body[0]["killed"] is False


def test_an_advertiser_with_no_copy_has_an_empty_library(client):
    assert client.get("/accounts/SLUMB/copy", headers=READER).json() == []
