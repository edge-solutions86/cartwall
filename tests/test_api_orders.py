import pytest
from starlette.testclient import TestClient

from cartwall.api.app import create_app
from cartwall.api.security import HEADER, KeyRing
from cartwall.config import Settings
from cartwall.domain.account import Advertiser, Credit
from cartwall.persistence.base import create_all, engine_for, session_factory
from cartwall.persistence.repositories import AccountRepository, StationRepository
from support import MEMORY

# These two files test what the routes do, not who may call them. Every request
# carries a manager key so the gate is never the thing under test. The gate
# itself is tested in test_api_roles.py.
MANAGER = "mstone:manager:managerkey"
KEYS = {HEADER: "managerkey"}


@pytest.fixture
def client(cluster, accounts):
    settings = Settings(database_url=MEMORY)
    engine = engine_for(settings.database_url)
    create_all(engine)
    accounts.add_advertiser(Advertiser.build("SLOW", "Slow Pay", credit=Credit.HOLD))
    with session_factory(engine)() as session:
        StationRepository(session).save_cluster(cluster)
        AccountRepository(session).save_book(accounts)
        session.commit()
    app = create_app(settings, engine, KeyRing.parse(MANAGER))
    return TestClient(app, headers=KEYS)


def a_line(number=1, **kw):
    return {
        "number": number,
        "station": kw.pop("station", "KWLO"),
        "daypart": kw.pop("daypart", "AMD"),
        "grade": kw.pop("grade", "fixed"),
        "length": kw.pop("length", ":30"),
        "rate": kw.pop("rate", "60.00"),
        "flight": {
            "first": "2025-08-04",
            "last": "2025-08-29",
            "days": "M-F",
            "per_week": 12,
        },
        **kw,
    }


def an_order(number="25-1044", **kw):
    return {
        "number": number,
        "advertiser": kw.pop("advertiser", "HYVEE"),
        "written": "2025-07-21",
        "salesperson": "DHOLM",
        "lines": kw.pop("lines", [a_line()]),
        **kw,
    }


def test_writing_an_order_gives_back_what_was_written(client):
    response = client.post("/orders", json=an_order())
    assert response.status_code == 201
    body = response.json()
    assert body["number"] == "25-1044"
    assert body["status"] == "pending"
    assert body["value"] == "2880.00"
    assert body["spots"] == 48


def test_an_order_can_be_read_back(client):
    client.post("/orders", json=an_order())
    body = client.get("/orders/25-1044").json()
    assert body["advertiser"] == "HYVEE"
    assert body["lines"][0]["flight"]["days"] == "M-F"


def test_reading_an_order_that_is_not_on_file(client):
    response = client.get("/orders/25-9999")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"
    assert response.json()["context"]["order"] == "25-9999"


def test_the_same_number_cannot_be_written_twice(client):
    client.post("/orders", json=an_order())
    response = client.post("/orders", json=an_order())
    assert response.status_code == 409
    assert response.json()["code"] == "conflict"
    assert "already on file" in response.json()["message"]


def test_a_body_that_is_not_the_right_shape_is_a_422(client):
    response = client.post("/orders", json={"number": "25-1044"})
    assert response.status_code == 422
    assert response.json()["code"] == "invalid"
    assert response.json()["context"]["problems"]


def test_money_sent_as_a_json_number_is_refused(client):
    body = an_order()
    body["lines"][0]["rate"] = 60.0
    response = client.post("/orders", json=body)
    assert response.status_code == 422
    problems = response.json()["context"]["problems"]
    assert any("send money as a string" in problem["why"] for problem in problems)


def test_a_length_the_house_does_not_sell_is_refused_by_the_domain(client):
    body = an_order()
    body["lines"][0]["length"] = ":25"
    response = client.post("/orders", json=body)
    assert response.status_code == 422
    assert response.json()["code"] == "bad_spot"


def test_an_order_can_be_rewritten_while_it_is_pending(client):
    client.post("/orders", json=an_order())
    revised = an_order(lines=[a_line(1), a_line(2, station="KCRR", rate="40.00")])
    response = client.put("/orders/25-1044", json=revised)
    assert response.status_code == 200
    assert len(response.json()["lines"]) == 2
    assert client.get("/orders/25-1044").json()["value"] == "4800.00"


def test_the_path_and_the_body_have_to_agree(client):
    client.post("/orders", json=an_order())
    response = client.put("/orders/25-1044", json=an_order("25-2000"))
    assert response.status_code == 422
    assert response.json()["code"] == "bad_order"
    assert response.json()["context"] == {"path": "25-1044", "body": "25-2000"}


def test_confirming_an_order_makes_it_schedulable(client):
    client.post("/orders", json=an_order())
    response = client.post("/orders/25-1044/confirm")
    assert response.status_code == 200
    assert response.json()["status"] == "confirmed"
    assert client.get("/orders/25-1044").json()["status"] == "confirmed"


def test_an_advertiser_on_hold_cannot_have_an_order_confirmed(client):
    client.post("/orders", json=an_order(advertiser="SLOW"))
    response = client.post("/orders/25-1044/confirm")
    assert response.status_code == 409
    assert "credit hold" in response.json()["message"]
    assert client.get("/orders/25-1044").json()["status"] == "pending"


def test_a_confirmed_order_is_not_rewritten_in_place(client):
    client.post("/orders", json=an_order())
    client.post("/orders/25-1044/confirm")
    response = client.put("/orders/25-1044", json=an_order(lines=[a_line(1), a_line(2)]))
    assert response.status_code == 409
    assert "cancel it rather than rewriting" in response.json()["message"]


def test_cancelling_an_order_takes_it_out_of_the_run(client):
    client.post("/orders", json=an_order())
    client.post("/orders/25-1044/confirm")
    assert client.post("/orders/25-1044/cancel").json()["status"] == "cancelled"


def test_a_cancelled_order_does_not_come_back(client):
    client.post("/orders", json=an_order())
    client.post("/orders/25-1044/cancel")
    response = client.post("/orders/25-1044/confirm")
    assert response.status_code == 422
    assert "was cancelled" in response.json()["message"]


def test_the_orders_of_one_advertiser_come_back_in_number_order(client):
    client.post("/orders", json=an_order("25-2000"))
    client.post("/orders", json=an_order("25-1044"))
    client.post("/orders", json=an_order("25-3000", advertiser="SLUMB"))
    body = client.get("/orders", params={"advertiser": "HYVEE"}).json()
    assert [order["number"] for order in body] == ["25-1044", "25-2000"]
    assert client.get("/orders", params={"advertiser": "NOBODY"}).json() == []


def test_listing_without_an_advertiser_is_refused(client):
    assert client.get("/orders").status_code == 422


def test_a_pending_order_can_be_deleted(client):
    client.post("/orders", json=an_order())
    assert client.delete("/orders/25-1044").status_code == 204
    assert client.get("/orders/25-1044").status_code == 404


def test_a_confirmed_order_is_cancelled_rather_than_deleted(client):
    client.post("/orders", json=an_order())
    client.post("/orders/25-1044/confirm")
    response = client.delete("/orders/25-1044")
    assert response.status_code == 409
    assert "cancel it rather than deleting" in response.json()["message"]


def test_deleting_something_that_is_not_there(client):
    assert client.delete("/orders/25-9999").status_code == 404


def test_a_failed_write_leaves_nothing_behind(client):
    body = an_order()
    body["lines"][0]["length"] = ":25"
    client.post("/orders", json=body)
    assert client.get("/orders/25-1044").status_code == 404
