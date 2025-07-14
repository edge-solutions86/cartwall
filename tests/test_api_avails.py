import pytest
from starlette.testclient import TestClient

from cartwall.airtime import Duration
from cartwall.api.app import create_app
from cartwall.api.security import HEADER, KeyRing
from cartwall.config import Settings
from cartwall.domain.daypart import standard_grid
from cartwall.domain.spot import THIRTY, Cut
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work
from support import CARD_DATE, MONDAY

RING = "eyre:reader:readerkey;tronan:traffic:traffickey"
READER = {HEADER: "readerkey"}
TRAFFIC = {HEADER: "traffickey"}


@pytest.fixture
def client(tmp_path, cluster, accounts, cards, programming):
    settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'avails.sqlite3'}")
    engine = engine_for(settings.database_url)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        for call in ("KWLO", "KOEL-FM", "KCRR", "KXEL", "KCNZ"):
            uow.cards.file(cards.in_force(call, CARD_DATE))
            uow.stations.save_dayparts(call, standard_grid())
            uow.stations.save_grid(programming[call])
        uow.accounts.file_cut(
            Cut(
                cart="C1204",
                isci="HYVE2501",
                advertiser="HYVEE",
                length=THIRTY,
                actual=Duration(300),
                first_air=MONDAY,
            )
        )
        uow.commit()
    return TestClient(create_app(settings, engine, KeyRing.parse(RING)))


def an_order(client, per_week=10):
    client.post(
        "/orders",
        json={
            "number": "25-1044",
            "advertiser": "HYVEE",
            "written": "2025-07-21",
            "salesperson": "DHOLM",
            "lines": [
                {
                    "number": 1,
                    "station": "KWLO",
                    "daypart": "AMD",
                    "grade": "fixed",
                    "length": ":30",
                    "rate": "60.00",
                    "carts": ["C1204"],
                    "flight": {
                        "first": "2025-08-04",
                        "last": "2025-08-08",
                        "days": "M-F",
                        "per_week": per_week,
                    },
                }
            ],
        },
        headers=TRAFFIC,
    )
    client.post("/orders/25-1044/confirm", headers=TRAFFIC)
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)


def test_reading_avails_needs_only_a_reader(client):
    an_order(client)
    assert client.get("/avails/KWLO/2025-08-04", headers=READER).status_code == 200


def test_a_day_with_nothing_sold_is_open_everywhere(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    body = client.get("/avails/KWLO/2025-08-04", headers=READER).json()
    rows = {row["label"]: row for row in body["rows"]}
    assert rows["AMD"]["units"] == 96
    assert rows["AMD"]["sold"] == 0
    assert rows["AMD"]["sellout"] == 0.0
    assert rows["AMD"]["value"] == "3456.00"


def test_the_response_says_the_rows_overlap(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    body = client.get("/avails/KWLO/2025-08-04", headers=READER).json()
    assert body["overlapping"] is True
    labels = [row["label"] for row in body["rows"]]
    assert labels.index("AMD") < labels.index("AAA") < labels.index("ROS")


def test_a_sold_unit_shows_in_every_daypart_that_covers_it(client):
    an_order(client)
    body = client.get("/avails/KWLO/2025-08-04", headers=READER).json()
    rows = {row["label"]: row for row in body["rows"]}
    assert rows["AMD"]["sold"] == 2
    assert rows["AAA"]["sold"] == 2
    assert rows["ROS"]["sold"] == 2
    assert rows["MID"]["sold"] == 0


def test_the_hour_report_runs_the_whole_day(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    body = client.get("/avails/KWLO/2025-08-04/hours", headers=READER).json()
    assert len(body["rows"]) == 24
    assert body["rows"][0]["label"] == "12:00a"
    assert body["rows"][7]["label"] == "7:00a"


def test_the_thinnest_hours_can_be_asked_for_on_their_own(client):
    an_order(client, per_week=20)
    body = client.get(
        "/avails/KWLO/2025-08-04/hours", params={"thinnest": 3}, headers=READER
    ).json()
    assert len(body["rows"]) == 3
    assert body["rows"][0]["open"] <= body["rows"][1]["open"]


def test_asking_for_more_thinnest_hours_than_there_are_is_refused(client):
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    response = client.get("/avails/KWLO/2025-08-04/hours", params={"thinnest": 99}, headers=READER)
    assert response.status_code == 422


def test_a_translator_reports_its_parents_inventory(client):
    an_order(client)
    body = client.get("/avails/KWLO-LP/2025-08-04", headers=READER).json()
    assert body["station"] == "KWLO"


def test_a_day_nobody_built_is_a_404(client):
    response = client.get("/avails/KWLO/2025-08-04", headers=READER)
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"


def test_a_week_rolls_the_days_up_and_gives_one_row_each(client):
    an_order(client)
    body = client.get("/avails/KWLO/week/2025-08-06", headers=READER).json()
    assert body["week"] == "week of 2025-08-04"
    assert body["first"] == "2025-08-04"
    assert body["last"] == "2025-08-10"
    assert len(body["days"]) == 7
    assert body["days"][0]["label"] == "Mon 08/04"
    assert body["units"] == 7 * 24 * 24
    assert body["sold"] == 10
    assert body["open"] == body["units"] - 10


def test_a_week_with_no_logs_built_is_a_404(client):
    response = client.get("/avails/KWLO/week/2025-09-01", headers=READER)
    assert response.status_code == 404
    assert "no logs built" in response.json()["message"]


def test_a_request_with_no_key_gets_nowhere(client):
    an_order(client)
    assert client.get("/avails/KWLO/2025-08-04").status_code == 401
