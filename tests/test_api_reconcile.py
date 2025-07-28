import pytest
from starlette.testclient import TestClient

from cartwall.airtime import AirTime, Duration
from cartwall.api.app import create_app
from cartwall.api.security import HEADER, KeyRing
from cartwall.config import Settings
from cartwall.domain.daypart import standard_grid
from cartwall.domain.spot import THIRTY, Cut
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work
from cartwall.services.audit import about
from support import CARD_DATE, MEMORY, MONDAY

RING = "eyre:reader:readerkey;tronan:traffic:traffickey;mstone:manager:managerkey"
READER = {HEADER: "readerkey"}
TRAFFIC = {HEADER: "traffickey"}
MANAGER = {HEADER: "managerkey"}

COLUMNS = "date,time,cart,title,length,status,reference"


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
    return engine


@pytest.fixture
def client(engine):
    settings = Settings(database_url=MEMORY)
    client = TestClient(create_app(settings, engine, KeyRing.parse(RING)))
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
                        "per_week": 10,
                    },
                }
            ],
        },
        headers=TRAFFIC,
    )
    client.post("/orders/25-1044/confirm", headers=TRAFFIC)
    client.post("/logs/build/2025-08-04", headers=TRAFFIC)
    return client


def scheduled(client, day="2025-08-04"):
    """The spots the log actually carries, so the as-run can answer them."""
    body = client.get(f"/logs/KWLO/{day}", params={"spots": True}, headers=READER).json()
    return [row for row in body["lines"] if row["order"] == "25-1044"]


def a_file(*lines: str) -> str:
    return "\n".join([COLUMNS, *lines]) + "\n"


def a_line(row, at=None, status="played", reference=None, length="30.0", day="2025-08-04"):
    """One as-run line answering a scheduled spot, with room to spoil it."""
    when = at if at is not None else row["at"]
    if reference is None:
        reference = f"{row['order']}-{row['line']}"
    return f"{day},{when},{row['cart']},Hy-Vee sale,{length},{status},{reference}"


def played(client, *, drop=0, day="2025-08-04"):
    rows = scheduled(client, day)
    kept = rows[: len(rows) - drop] if drop else rows
    return a_file(*(a_line(row, day=day) for row in kept))


def air(client, day="2025-08-04"):
    """A log only reaches aired through approved and released."""
    client.post(f"/logs/KWLO/{day}/approve", json={"who": "tronan"}, headers=TRAFFIC)
    client.post(f"/logs/KWLO/{day}/release", json={"who": "tronan"}, headers=TRAFFIC)
    return client.post(f"/reconcile/KWLO/{day}/aired", headers=MANAGER)


def test_the_fixture_puts_two_spots_on_monday(client):
    assert len(scheduled(client)) == 2


def test_checking_an_as_run_needs_traffic(client):
    text = played(client)
    denied = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=READER)
    assert denied.status_code == 403
    response = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC)
    assert response.status_code == 200


def test_a_file_that_answers_every_spot_is_clean(client):
    body = client.post(
        "/reconcile/KWLO/2025-08-04/check", content=played(client), headers=TRAFFIC
    ).json()
    assert body["clean"] is True
    assert body["scheduled"] == 2
    assert body["aired"] == 2
    assert body["missed"] == []
    assert body["extra"] == []


def test_a_matched_spot_says_it_was_found_by_reference(client):
    body = client.post(
        "/reconcile/KWLO/2025-08-04/check", content=played(client), headers=TRAFFIC
    ).json()
    first = body["matched"][0]
    assert first["by_reference"] is True
    assert first["order"] == "25-1044"
    assert first["drift_seconds"] == 0
    assert first["on_time"] is True
    assert first["status"] == "played"


def test_a_spot_the_automation_never_ran_comes_back_missed(client):
    body = client.post(
        "/reconcile/KWLO/2025-08-04/check", content=played(client, drop=1), headers=TRAFFIC
    ).json()
    assert body["clean"] is False
    assert len(body["missed"]) == 1
    assert body["missed"][0]["order"] == "25-1044"
    assert body["missed"][0]["reason"] == "no as-run line"


def test_the_owed_tally_counts_the_line_short(client):
    body = client.post(
        "/reconcile/KWLO/2025-08-04/check", content=played(client, drop=2), headers=TRAFFIC
    ).json()
    assert body["owed"] == [{"order": "25-1044", "line": 1, "spots": 2}]


def test_a_spot_that_drifted_an_hour_is_moved_and_still_aired(client):
    rows = scheduled(client)
    moved = AirTime.parse(rows[0]["at"]) + Duration.of_seconds(3600)
    text = a_file(a_line(rows[0], at=moved.twelve_hour()), a_line(rows[1]))
    body = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC).json()
    assert body["aired"] == 2
    assert body["missed"] == []
    assert body["clean"] is False
    drifted = [row for row in body["matched"] if row["drift_seconds"]]
    assert drifted[0]["drift_seconds"] == 3600
    assert drifted[0]["on_time"] is False


def test_a_cart_nobody_logged_comes_back_as_extra(client):
    rows = scheduled(client)
    stray = {"at": "3:20:00p", "cart": "C9999", "order": "", "line": 0}
    text = a_file(*(a_line(row) for row in rows), a_line(stray, reference=""))
    body = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC).json()
    assert [row["cart"] for row in body["extra"]] == ["C9999"]
    assert body["extra"][0]["aired_at"] == "3:20:00p"


def test_a_spot_with_no_reference_still_matches_on_cart_and_time(client):
    rows = scheduled(client)
    text = a_file(*(a_line(row, reference="") for row in rows))
    body = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC).json()
    assert body["scheduled"] == 2
    assert body["missed"] == []
    assert [row["by_reference"] for row in body["matched"]] == [False, False]


def test_a_file_for_the_wrong_day_is_refused(client):
    rows = scheduled(client)
    text = a_file(*(a_line(row, day="2025-08-05") for row in rows))
    response = client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC)
    assert response.status_code == 422
    assert response.json()["code"] == "bad_reconcile"


def test_a_file_with_the_wrong_columns_is_refused(client):
    response = client.post(
        "/reconcile/KWLO/2025-08-04/check", content="when,what\n1,2\n", headers=TRAFFIC
    )
    assert response.status_code == 422
    assert response.json()["code"] == "bad_asrun"


def test_a_day_nobody_built_has_nothing_to_compare(client):
    response = client.post("/reconcile/KWLO/2025-09-08/check", content=a_file(), headers=TRAFFIC)
    assert response.status_code == 404
    assert "no log" in response.json()["message"]


def test_posting_against_a_day_that_has_not_aired_is_refused(client):
    response = client.post("/reconcile/KWLO/2025-08-04", content=played(client), headers=TRAFFIC)
    assert response.status_code == 409
    assert response.json()["code"] == "not_aired"


def test_a_day_that_has_not_aired_can_still_be_forced(client):
    response = client.post(
        "/reconcile/KWLO/2025-08-04",
        content=played(client),
        params={"force": True},
        headers=TRAFFIC,
    )
    assert response.status_code == 200
    assert response.json()["aired"] == 2


def test_saying_the_day_went_out_needs_a_manager(client):
    client.post("/logs/KWLO/2025-08-04/approve", json={"who": "tronan"}, headers=TRAFFIC)
    client.post("/logs/KWLO/2025-08-04/release", json={"who": "tronan"}, headers=TRAFFIC)
    assert client.post("/reconcile/KWLO/2025-08-04/aired", headers=TRAFFIC).status_code == 403
    assert client.post("/reconcile/KWLO/2025-08-04/aired", headers=MANAGER).status_code == 200


def test_a_log_that_never_went_out_cannot_be_called_aired(client):
    response = client.post("/reconcile/KWLO/2025-08-04/aired", headers=MANAGER)
    assert response.status_code == 409
    assert "did not go to air" in response.json()["message"]


def test_posting_an_aired_day_counts_what_went_out(client):
    text = played(client, drop=1)
    air(client)
    body = client.post("/reconcile/KWLO/2025-08-04", content=text, headers=TRAFFIC).json()
    assert body["aired"] == 1
    assert body["missed"] == 1
    assert body["moved"] == 0
    assert body["unlogged"] == 0


def test_checking_writes_nothing(client):
    text = played(client)
    air(client)
    client.post("/reconcile/KWLO/2025-08-04/check", content=text, headers=TRAFFIC)
    assert (
        client.get(
            "/reconcile/order/25-1044",
            params={"first": "2025-08-04", "last": "2025-08-08"},
            headers=READER,
        ).json()
        == []
    )


def test_posting_lands_on_the_trail(client, engine):
    text = played(client)
    air(client)
    client.post("/reconcile/KWLO/2025-08-04", content=text, headers=TRAFFIC)
    with unit_of_work(engine) as uow:
        trail = about(uow.session, "log", "KWLO 2025-08-04")
    assert trail[-1].action == "asrun.posted"
    assert trail[-1].actor == "tronan"
    assert "aired=2" in trail[-1].detail


def test_what_aired_reads_back_against_the_order(client):
    text = played(client)
    air(client)
    client.post("/reconcile/KWLO/2025-08-04", content=text, headers=TRAFFIC)
    body = client.get(
        "/reconcile/order/25-1044",
        params={"first": "2025-08-04", "last": "2025-08-08"},
        headers=READER,
    ).json()
    assert len(body) == 2
    assert body[0]["advertiser"] == "HYVEE"
    assert body[0]["status"] == "played"
    assert body[0]["aired_at"] == body[0]["scheduled_at"]


def test_what_was_missed_reads_back_against_the_order(client):
    text = played(client, drop=1)
    air(client)
    client.post("/reconcile/KWLO/2025-08-04", content=text, headers=TRAFFIC)
    body = client.get(
        "/reconcile/order/25-1044",
        params={"first": "2025-08-04", "last": "2025-08-08", "aired": False},
        headers=READER,
    ).json()
    assert len(body) == 1
    assert body[0]["aired_at"] == ""


def test_a_span_that_runs_backwards_is_refused(client):
    response = client.get(
        "/reconcile/order/25-1044",
        params={"first": "2025-08-08", "last": "2025-08-04"},
        headers=READER,
    )
    assert response.status_code == 404


def test_a_request_with_no_key_gets_nowhere(client):
    assert client.post("/reconcile/KWLO/2025-08-04/check", content=a_file()).status_code == 401
