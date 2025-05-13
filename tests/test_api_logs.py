from datetime import date

import pytest
from starlette.testclient import TestClient

from cartwall.airtime import Duration
from cartwall.api.app import create_app
from cartwall.config import Settings
from cartwall.domain.daypart import standard_grid
from cartwall.domain.spot import THIRTY, Cut
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work
from support import CARD_DATE, MONDAY

FRIDAY = date(2025, 8, 8)
SIGNED = "2025-08-01T16:30:00"


@pytest.fixture
def client(tmp_path, cluster, accounts, cards, programming):
    settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'logs.sqlite3'}")
    engine = engine_for(settings.database_url)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_dayparts(call, standard_grid())
            uow.stations.save_grid(programming[call])
        # Production dubs the copy. Traffic names a cart that has been dubbed.
        for cart, isci in (("C1204", "HYVE2501"), ("C1205", "HYVE2502")):
            uow.accounts.file_cut(
                Cut(
                    cart=cart,
                    isci=isci,
                    advertiser="HYVEE",
                    length=THIRTY,
                    actual=Duration(300),
                    first_air=MONDAY,
                )
            )
        uow.commit()
    return TestClient(create_app(settings, engine))


def a_line(number=1, **kw):
    return {
        "number": number,
        "station": kw.pop("station", "KWLO"),
        "daypart": kw.pop("daypart", "AMD"),
        "grade": "fixed",
        "length": ":30",
        "rate": "60.00",
        "carts": kw.pop("carts", ["C1204"]),
        "flight": {
            "first": "2025-08-04",
            "last": "2025-08-08",
            "days": "M-F",
            "per_week": kw.pop("per_week", 10),
        },
    }


def an_order(client, number="25-1044", lines=None, confirm=True):
    body = {
        "number": number,
        "advertiser": "HYVEE",
        "written": "2025-07-21",
        "salesperson": "DHOLM",
        "lines": lines if lines is not None else [a_line()],
    }
    client.post("/orders", json=body)
    if confirm:
        client.post(f"/orders/{number}/confirm")


def test_building_a_week_writes_a_log_for_every_station_day(client):
    an_order(client)
    body = client.post("/logs/build/2025-08-06").json()
    assert body["week"] == "week of 2025-08-04"
    assert body["placed"] == 10
    assert body["wanted"] == 10
    assert body["complete"]
    assert body["written"] == 35
    assert body["skipped"] == []


def test_a_built_log_reads_back_in_air_order(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO/2025-08-04").json()
    assert body["station"] == "KWLO"
    assert body["state"] == "draft"
    assert body["revision"] == 0
    hours = [line["hour"] for line in body["lines"]]
    assert hours == sorted(hours)
    assert body["sold_units"] == 2


def test_the_log_carries_the_fixed_elements_as_well_as_the_spots(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO/2025-08-04").json()
    kinds = {line["element"] for line in body["lines"]}
    assert "legal id" in kinds
    assert "spot" in kinds
    assert "unsold" in kinds


def test_sales_can_ask_for_the_spots_and_nothing_else(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO/2025-08-04", params={"spots": True}).json()
    assert {line["element"] for line in body["lines"]} == {"spot"}
    assert len(body["lines"]) == 2
    assert body["lines"][0]["advertiser"] == "HYVEE"
    assert body["lines"][0]["cart"] == "C1204"


def test_a_log_that_was_never_built(client):
    response = client.get("/logs/KWLO/2025-08-04")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_a_spot_bought_on_the_translator_is_read_off_the_parents_log(client):
    an_order(client, lines=[a_line(station="KWLO-LP")])
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO-LP/2025-08-04").json()
    assert body["station"] == "KWLO"
    assert body["sold_units"] == 2


def test_a_log_with_copy_on_every_spot_is_ready_to_sign(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO/2025-08-04/ready").json()
    assert body["ready"]
    assert body["problems"] == []
    assert body["warnings"][0]["code"] == "unsold"


def test_a_spot_with_no_copy_stops_the_log(client):
    an_order(client, lines=[a_line(carts=[])])
    client.post("/logs/build/2025-08-04")
    body = client.get("/logs/KWLO/2025-08-04/ready").json()
    assert not body["ready"]
    assert body["problems"][0]["code"] == "missing_copy"
    assert "25-1044-1" in body["problems"][0]["detail"]


def test_a_log_with_a_problem_is_not_signed(client):
    an_order(client, lines=[a_line(carts=[])])
    client.post("/logs/build/2025-08-04")
    response = client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    assert response.status_code == 409
    assert "problem" in response.json()["message"]


def test_signing_a_clean_log_records_who_signed_it(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    body = client.post(
        "/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan", "at": SIGNED}
    ).json()
    assert body["state"] == "approved"
    assert body["approved_by"] == "T. Ronan"


def test_a_log_has_to_be_signed_by_somebody(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    response = client.post("/logs/KWLO/2025-08-04/approve", json={"who": ""})
    assert response.status_code == 409


def test_a_log_is_not_signed_twice(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    response = client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    assert response.status_code == 409
    assert "not a draft" in response.json()["message"]


def test_a_log_has_to_be_signed_before_it_goes_out(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    response = client.post("/logs/KWLO/2025-08-04/release", json={"who": "T. Ronan"})
    assert response.status_code == 409
    assert "not been approved" in response.json()["message"]


def test_a_signed_log_goes_out(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    body = client.post("/logs/KWLO/2025-08-04/release", json={"who": "T. Ronan"}).json()
    assert body["state"] == "released"


def test_a_released_day_is_left_alone_by_the_next_build(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    client.post("/logs/KWLO/2025-08-04/release", json={"who": "T. Ronan"})
    body = client.post("/logs/build/2025-08-04").json()
    assert "KWLO 2025-08-04 released" in body["skipped"]
    assert body["written"] == 34


def test_revising_a_released_log_opens_it_back_up(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    client.post("/logs/KWLO/2025-08-04/release", json={"who": "T. Ronan"})
    body = client.post("/logs/KWLO/2025-08-04/revise").json()
    assert body["state"] == "draft"
    assert body["revision"] == 1
    assert body["approved_by"] == ""


def test_a_draft_log_does_not_need_a_revision(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    response = client.post("/logs/KWLO/2025-08-04/revise")
    assert response.status_code == 409
    assert "does not need a revision" in response.json()["message"]


def test_a_span_of_days_lists_what_state_each_one_is_in(client):
    an_order(client)
    client.post("/logs/build/2025-08-04")
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "T. Ronan"})
    body = client.get("/logs/KWLO", params={"first": "2025-08-04", "last": "2025-08-10"}).json()
    assert len(body) == 7
    assert body[0] == {
        "day": "2025-08-04",
        "state": "approved",
        "revision": 0,
        "approved_by": "T. Ronan",
    }
    assert body[1]["state"] == "draft"


def test_a_span_that_runs_backwards_is_refused(client):
    response = client.get("/logs/KWLO", params={"first": "2025-08-10", "last": "2025-08-04"})
    assert response.status_code == 404
    assert "runs backwards" in response.json()["message"]
