from datetime import date

import pytest
from sqlalchemy import select

from cartwall.config import ConfigError, Settings
from cartwall.domain.order import Flight, Line, Order
from cartwall.domain.ratecard import ClassOfTime
from cartwall.domain.spot import THIRTY
from cartwall.errors import LookupFailure
from cartwall.money import Money
from cartwall.persistence.models.trading import OrderRow
from cartwall.persistence.unitofwork import (
    UnitOfWork,
    UnitOfWorkError,
    open_database,
    unit_of_work,
)
from support import MONDAY

FIXED = ClassOfTime.FIXED


@pytest.fixture
def settings(tmp_path):
    return Settings(database_url=f"sqlite+pysqlite:///{tmp_path / 'uow.sqlite3'}")


@pytest.fixture
def engine(settings, cluster, accounts):
    engine = open_database(settings, build=True)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.accounts.save_book(accounts)
        uow.commit()
    return engine


def an_order(number="25-1044"):
    return Order(
        number=number,
        advertiser="HYVEE",
        written=date(2025, 7, 21),
        salesperson="DHOLM",
        lines=(
            Line(
                number=1,
                station="KWLO",
                daypart="AMD",
                grade=FIXED,
                length=THIRTY,
                rate=Money.dollars(60),
                flight=Flight.build(MONDAY, date(2025, 8, 29), "M-F", 12),
            ),
        ),
    )


def test_the_defaults_are_the_ones_the_house_runs():
    settings = Settings()
    assert settings.horizon_days == 14
    assert settings.strict_copy
    assert not settings.echo_sql
    assert settings.is_sqlite
    assert "Waterloo" in str(settings)


def test_settings_that_cannot_be_used():
    with pytest.raises(ConfigError, match="no database was configured"):
        Settings(database_url="")
    with pytest.raises(ConfigError, match="not a horizon"):
        Settings(horizon_days=0)
    with pytest.raises(ConfigError, match="further out than any card"):
        Settings(horizon_days=200)


def test_the_environment_is_read_once_and_parsed_strictly(monkeypatch):
    monkeypatch.setenv("CARTWALL_DATABASE_URL", "postgresql://host/cartwall")
    monkeypatch.setenv("CARTWALL_HORIZON_DAYS", "21")
    monkeypatch.setenv("CARTWALL_ECHO_SQL", "yes")
    monkeypatch.setenv("CARTWALL_STRICT_COPY", "off")
    settings = Settings.from_environment()
    assert settings.database_url == "postgresql://host/cartwall"
    assert settings.horizon_days == 21
    assert settings.echo_sql
    assert not settings.strict_copy
    assert not settings.is_sqlite


def test_a_setting_that_is_not_a_number_or_a_yes_says_so(monkeypatch):
    monkeypatch.setenv("CARTWALL_HORIZON_DAYS", "a fortnight")
    with pytest.raises(ConfigError, match="not a number"):
        Settings.from_environment()
    monkeypatch.delenv("CARTWALL_HORIZON_DAYS")
    monkeypatch.setenv("CARTWALL_ECHO_SQL", "maybe")
    with pytest.raises(ConfigError, match="not a yes or a no"):
        Settings.from_environment()


def test_pointing_settings_at_another_database_leaves_the_rest_alone():
    settings = Settings(horizon_days=21, strict_copy=False)
    moved = settings.with_database("sqlite+pysqlite:///other.sqlite3")
    assert moved.horizon_days == 21
    assert not moved.strict_copy
    assert moved.database_url.endswith("other.sqlite3")


def test_the_repositories_all_share_one_session(engine):
    with unit_of_work(engine) as uow:
        assert uow.orders.session is uow.session
        assert uow.stations.session is uow.session
        assert uow.accounts.session is uow.session
        assert uow.cards.session is uow.session


def test_what_one_repository_writes_the_next_one_sees_without_a_commit(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        uow.flush()
        assert uow.orders.exists("25-1044")
        uow.commit()


def test_leaving_the_block_without_committing_rolls_back(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        assert not uow.committed

    with unit_of_work(engine) as uow:
        assert not uow.orders.exists("25-1044")


def test_committing_makes_it_stick(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        uow.commit()
        assert uow.committed

    with unit_of_work(engine) as uow:
        assert uow.orders.get("25-1044").advertiser == "HYVEE"


def test_an_exception_inside_the_block_rolls_back_and_still_raises(engine):
    with pytest.raises(LookupFailure), unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        uow.orders.get("25-9999")

    with unit_of_work(engine) as uow:
        assert not uow.orders.exists("25-1044")


def test_an_explicit_rollback_undoes_the_work_and_clears_the_flag(engine):
    with unit_of_work(engine) as uow:
        uow.orders.save(an_order())
        uow.commit()
        uow.orders.save(an_order("25-2000"))
        uow.rollback()
        assert not uow.committed

    with unit_of_work(engine) as uow:
        assert uow.orders.exists("25-1044")
        assert not uow.orders.exists("25-2000")


def test_a_closed_unit_of_work_will_not_be_used_again(engine):
    with unit_of_work(engine) as uow:
        uow.commit()
    with pytest.raises(UnitOfWorkError, match="already been closed"):
        uow.commit()
    with pytest.raises(UnitOfWorkError, match="already been closed"):
        uow.flush()


def test_closing_twice_is_harmless(engine):
    with unit_of_work(engine) as uow:
        uow.commit()
    uow.close()
    uow.close()


def test_a_unit_of_work_can_be_built_on_a_session_directly(engine):
    from cartwall.persistence.base import session_factory

    with session_factory(engine)() as session:
        uow = UnitOfWork(session)
        uow.orders.save(an_order())
        uow.commit()
        assert session.scalars(select(OrderRow)).one().number == "25-1044"


def test_opening_a_database_without_building_it_finds_no_tables(settings):
    engine = open_database(settings, build=False)
    from sqlalchemy import inspect

    assert inspect(engine).get_table_names() == []
    assert inspect(open_database(settings, build=True)).get_table_names() != []
