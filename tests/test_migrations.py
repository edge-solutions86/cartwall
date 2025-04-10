"""The migration and the models have to agree, and only a test can say so.

A migration written by hand drifts from the models the first time somebody adds
a column and forgets. The check is not "does it run", it is "does what it
builds match what the mappers expect", so these compare the built schema
against the metadata table by table and column by column.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import inspect

from cartwall.persistence.base import Base, create_all, engine_for

ROOT = Path(__file__).resolve().parent.parent
IGNORED = {"alembic_version"}


def alembic_config(url: str) -> Config:
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


@pytest.fixture(scope="module")
def migrated(tmp_path_factory):
    """One database built by running the migrations, shared by this module."""
    path = tmp_path_factory.mktemp("migrated") / "migrated.sqlite3"
    url = f"sqlite+pysqlite:///{path}"
    command.upgrade(alembic_config(url), "head")
    return engine_for(url)


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    """The same schema built straight off the metadata, to compare against."""
    path = tmp_path_factory.mktemp("built") / "built.sqlite3"
    engine = engine_for(f"sqlite+pysqlite:///{path}")
    create_all(engine)
    return engine


def tables(engine) -> set[str]:
    return set(inspect(engine).get_table_names()) - IGNORED


def test_the_migration_builds_every_table_the_models_declare(migrated):
    assert tables(migrated) == set(Base.metadata.tables)


def test_the_migration_builds_the_same_tables_as_the_metadata(migrated, built):
    assert tables(migrated) == tables(built)


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_column_matches_the_model(migrated, name):
    declared = {column.name: column for column in Base.metadata.tables[name].columns}
    found = {column["name"]: column for column in inspect(migrated).get_columns(name)}
    assert set(found) == set(declared), name
    for column, spec in found.items():
        assert spec["nullable"] == declared[column].nullable, f"{name}.{column}"


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_column_type_matches_the_model(migrated, built, name):
    left = {c["name"]: str(c["type"]) for c in inspect(migrated).get_columns(name)}
    right = {c["name"]: str(c["type"]) for c in inspect(built).get_columns(name)}
    assert left == right, name


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_primary_key_matches_the_model(migrated, built, name):
    left = inspect(migrated).get_pk_constraint(name)
    right = inspect(built).get_pk_constraint(name)
    assert left["constrained_columns"] == right["constrained_columns"], name


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_unique_constraint_matches_the_model(migrated, built, name):
    def named(engine):
        return sorted(
            (con["name"], tuple(con["column_names"]))
            for con in inspect(engine).get_unique_constraints(name)
        )

    assert named(migrated) == named(built), name


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_foreign_key_matches_the_model(migrated, built, name):
    def keys(engine):
        return sorted(
            (
                tuple(fk["constrained_columns"]),
                fk["referred_table"],
                tuple(fk["referred_columns"]),
            )
            for fk in inspect(engine).get_foreign_keys(name)
        )

    assert keys(migrated) == keys(built), name


@pytest.mark.parametrize("name", sorted(Base.metadata.tables))
def test_every_index_matches_the_model(migrated, built, name):
    def indexes(engine):
        return sorted(
            (index["name"], tuple(index["column_names"]), bool(index["unique"]))
            for index in inspect(engine).get_indexes(name)
        )

    assert indexes(migrated) == indexes(built), name


def test_the_migration_reverses(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'reversed.sqlite3'}"
    config = alembic_config(url)
    command.upgrade(config, "head")
    engine = engine_for(url)
    assert tables(engine)
    command.downgrade(config, "base")
    assert tables(engine_for(url)) == set()


def test_upgrading_twice_is_a_no_op(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'twice.sqlite3'}"
    config = alembic_config(url)
    command.upgrade(config, "head")
    before = tables(engine_for(url))
    command.upgrade(config, "head")
    assert tables(engine_for(url)) == before


def test_the_counter_table_is_in_the_migration_even_though_it_lives_in_services():
    # It is declared in persistence.models.office rather than beside the service
    # that draws from it, so that create_all always builds it.
    assert "counter" in Base.metadata.tables
