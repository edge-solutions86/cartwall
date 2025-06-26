import pytest
from click.testing import CliRunner
from sqlalchemy import inspect

from cartwall import __version__
from cartwall.cli.main import MISUSED, OK, REFUSED, cli, parse_day, run
from cartwall.persistence.base import create_all, engine_for
from cartwall.persistence.unitofwork import unit_of_work


@pytest.fixture
def runner():
    return CliRunner()


@pytest.fixture
def url(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'cli.sqlite3'}"


@pytest.fixture
def ready(url, cluster):
    engine = engine_for(url)
    create_all(engine)
    with unit_of_work(engine) as uow:
        uow.stations.save_cluster(cluster)
        uow.commit()
    return url


def test_the_version_is_the_packages_version(runner):
    result = runner.invoke(cli, ["--version"])
    assert result.exit_code == 0
    assert __version__ in result.output


def test_help_lists_the_commands(runner):
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    for command in ("init-db", "drop-db", "status", "stations"):
        assert command in result.output


def test_building_the_schema_asks_first(runner, url):
    result = runner.invoke(cli, ["-d", url, "init-db"], input="n\n")
    assert result.exit_code != 0
    assert inspect(engine_for(url)).get_table_names() == []


def test_building_the_schema_when_told_to(runner, url):
    result = runner.invoke(cli, ["-d", url, "init-db", "--yes"])
    assert result.exit_code == 0
    assert "schema built" in result.output
    assert "station" in inspect(engine_for(url)).get_table_names()


def test_answering_yes_at_the_prompt_builds_it(runner, url):
    result = runner.invoke(cli, ["-d", url, "init-db"], input="y\n")
    assert result.exit_code == 0
    assert "station" in inspect(engine_for(url)).get_table_names()


def test_dropping_the_schema_asks_first(runner, ready):
    result = runner.invoke(cli, ["-d", ready, "drop-db"], input="n\n")
    assert result.exit_code != 0
    assert "station" in inspect(engine_for(ready)).get_table_names()


def test_dropping_the_schema_when_told_to(runner, ready):
    result = runner.invoke(cli, ["-d", ready, "drop-db", "--yes"])
    assert result.exit_code == 0
    assert inspect(engine_for(ready)).get_table_names() == []


def test_status_says_what_it_is_pointed_at(runner, ready):
    result = runner.invoke(cli, ["-d", ready, "status"])
    assert result.exit_code == 0
    assert "Waterloo" in result.output
    assert ready in result.output
    assert "14 days" in result.output
    assert "database  answering" in result.output


def test_status_says_when_the_database_will_not_answer(runner, tmp_path):
    bad = "sqlite+pysqlite:////nowhere/at/all/cli.sqlite3"
    result = runner.invoke(cli, ["-d", bad, "status"])
    assert result.exit_code == REFUSED
    assert "unreachable" in result.output


def test_the_station_list_marks_the_ones_that_get_a_log(runner, ready):
    result = runner.invoke(cli, ["-d", ready, "stations"])
    assert result.exit_code == 0
    assert "KWLO" in result.output
    assert "99.1 FM" in result.output
    assert "carries KWLO" in result.output
    assert "5 of 6 get a log" in result.output


def test_the_station_list_of_an_empty_database(runner, url):
    engine = engine_for(url)
    create_all(engine)
    result = runner.invoke(cli, ["-d", url, "stations"])
    assert result.exit_code == 0
    assert "no stations on file" in result.output


def test_the_database_can_be_overridden_on_the_command_line(runner, tmp_path, monkeypatch):
    monkeypatch.setenv("CARTWALL_DATABASE_URL", "sqlite+pysqlite:///wrong.sqlite3")
    right = f"sqlite+pysqlite:///{tmp_path / 'right.sqlite3'}"
    result = runner.invoke(cli, ["-d", right, "init-db", "--yes"])
    assert result.exit_code == 0
    assert "right.sqlite3" in result.output


def test_a_date_that_is_not_a_date_says_the_shape_it_wants():
    assert parse_day("2025-08-04").isoformat() == "2025-08-04"
    with pytest.raises(Exception, match="use YYYY-MM-DD"):
        parse_day("4 August")


def test_the_exit_codes_are_the_three_the_house_uses(url):
    assert run(["-d", url, "init-db", "--yes"]) == OK
    assert run(["-d", url, "--help"]) == OK
    assert run(["not-a-command"]) == MISUSED
    assert run(["-d", "sqlite+pysqlite:////nowhere/x.sqlite3", "status"]) == REFUSED


def test_stopping_at_a_prompt_is_a_refusal_and_not_a_crash(url, monkeypatch):
    monkeypatch.setattr(
        "click.confirm", lambda *a, **kw: (_ for _ in ()).throw(__import__("click").Abort())
    )
    assert run(["-d", url, "init-db"]) == REFUSED


def test_the_packages_own_logging_stays_quiet_while_a_command_runs(url, capsys):
    from cartwall.logging import configure, event

    configure("info")
    run(["-d", url, "init-db", "--yes"])
    event("after.the.command")
    caught = capsys.readouterr()
    assert "schema built" in caught.out
    assert "after.the.command" in caught.err
    assert "init" not in caught.err
