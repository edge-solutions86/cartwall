import pytest
from fastapi import APIRouter
from httpx import ASGITransport, AsyncClient
from starlette.testclient import TestClient

from cartwall import __version__
from cartwall.api.app import create_app
from cartwall.api.deps import Work
from cartwall.api.errors import body_for, status_for
from cartwall.config import Settings
from cartwall.errors import CartwallError, ConflictError, LookupFailure, ValidationError
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import UnitOfWork
from cartwall.scheduling.inventory import BreakFull


@pytest.fixture
def settings(tmp_path):
    return Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'api.sqlite3'}")


@pytest.fixture
def app(settings):
    engine = engine_for(settings.database_url)
    create_all(engine)
    return create_app(settings, engine)


@pytest.fixture
def client(app):
    return TestClient(app)


def test_a_validation_failure_is_a_422():
    assert status_for(ValidationError("no")) == 422


def test_something_that_is_not_on_file_is_a_404():
    assert status_for(LookupFailure("no")) == 404


def test_a_conflict_with_the_state_is_a_409():
    assert status_for(ConflictError("no")) == 409
    assert status_for(BreakFull("no")) == 409


def test_anything_else_is_a_500():
    assert status_for(CartwallError("no")) == 500


def test_the_body_is_the_code_the_message_and_the_context():
    failure = BreakFull("the break is full", hour=7, number=2)
    assert body_for(failure) == {
        "code": "break_full",
        "message": "the break is full",
        "context": {"hour": 7, "number": 2},
    }


def test_health_answers_without_touching_the_database(settings):
    app = create_app(settings, engine_for("sqlite+pysqlite:///:memory:"))
    body = TestClient(app).get("/health").json()
    assert body["status"] == "up"
    assert body["version"] == __version__
    assert body["market"] == settings.market


def test_ready_answers_when_the_database_is_there(client):
    response = client.get("/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "ready"}


def test_the_openapi_document_is_served(client):
    document = client.get("/openapi.json").json()
    assert document["info"]["title"] == "cartwall"
    assert document["info"]["version"] == __version__
    assert "/health" in document["paths"]


def test_a_domain_failure_comes_back_in_the_house_shape(app):
    router = APIRouter()

    @router.get("/boom")
    def boom():
        raise BreakFull("the break is full", hour=7, number=2)

    app.include_router(router)
    response = TestClient(app).get("/boom")
    assert response.status_code == 409
    assert response.json() == {
        "code": "break_full",
        "message": "the break is full",
        "context": {"hour": 7, "number": 2},
    }


def test_a_lookup_failure_comes_back_as_a_404_in_the_same_shape(app):
    router = APIRouter()

    @router.get("/missing")
    def missing():
        raise LookupFailure("order 25-9999 is not on file", order="25-9999")

    app.include_router(router)
    response = TestClient(app).get("/missing")
    assert response.status_code == 404
    assert response.json()["code"] == "not_found"
    assert response.json()["context"] == {"order": "25-9999"}


def test_a_body_of_the_wrong_shape_comes_back_in_the_house_shape_too(app):
    from pydantic import BaseModel

    class Body(BaseModel):
        count: int

    router = APIRouter()

    @router.post("/take")
    def take(body: Body):
        return {"count": body.count}

    app.include_router(router)
    response = TestClient(app).post("/take", json={"count": "a lot"})
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "invalid"
    assert "not the shape this route takes" in body["message"]
    assert body["context"]["problems"][0]["where"].endswith("count")
    assert body["context"]["problems"][0]["why"]


def test_a_handler_that_returns_gets_its_work_committed(app):
    router = APIRouter()

    @router.post("/counter")
    def take_a_number(uow: UnitOfWork = Work):
        from cartwall.services.numbering import Numbering

        return {"number": Numbering(uow.session).next("ORD", 2025)}

    app.include_router(router)
    client = TestClient(app)
    assert client.post("/counter").json() == {"number": "25-1001"}
    assert client.post("/counter").json() == {"number": "25-1002"}


def test_a_handler_that_raises_gets_its_work_rolled_back(app):
    router = APIRouter()

    @router.post("/counter/{fail}")
    def take_a_number(fail: str, uow: UnitOfWork = Work):
        from cartwall.services.numbering import Numbering

        number = Numbering(uow.session).next("ORD", 2025)
        if fail == "yes":
            raise ConflictError("changed my mind")
        return {"number": number}

    app.include_router(router)
    client = TestClient(app)
    assert client.post("/counter/no").json() == {"number": "25-1001"}
    assert client.post("/counter/yes").status_code == 409
    assert client.post("/counter/no").json() == {"number": "25-1002"}


@pytest.mark.anyio
async def test_the_application_also_answers_over_asgi_directly(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://house") as client:
        response = await client.get("/health")
    assert response.status_code == 200


@pytest.fixture
def anyio_backend():
    return "asyncio"


def test_two_applications_can_run_against_two_databases(tmp_path):
    apps = []
    for name in ("one", "two"):
        settings = Settings(database_url=f"sqlite+pysqlite:///{tmp_path / name}.sqlite3")
        engine = engine_for(settings.database_url)
        create_all(engine)
        apps.append(create_app(settings, engine))
    assert apps[0].state.engine is not apps[1].state.engine
    for app in apps:
        assert TestClient(app).get("/ready").status_code == 200
