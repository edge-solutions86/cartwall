import logging
from io import StringIO

import pytest

from cartwall.logging import (
    LEVEL_VAR,
    Formatter,
    configure,
    detail,
    event,
    failed,
    get_logger,
    line,
    pairs,
    quiet,
    render,
    warn,
)


@pytest.fixture
def caught():
    stream = StringIO()
    configure("debug", stream)
    yield stream
    configure("info", StringIO())


def written(stream) -> list[str]:
    return [text for text in stream.getvalue().splitlines() if text]


def test_a_value_is_written_so_it_cannot_split_a_field():
    assert render("KWLO") == "KWLO"
    assert render("Black Hawk Bank") == '"Black Hawk Bank"'
    assert render(None) == "-"
    assert render(True) == "yes"
    assert render(False) == "no"
    assert render("") == '""'
    assert render(12) == "12"


def test_a_quote_inside_a_value_does_not_close_the_quoting():
    assert render('Casey\'s "General" Store') == "\"Casey's 'General' Store\""


def test_pairs_keep_the_order_they_were_passed():
    assert pairs(station="KWLO", day="2025-08-04") == "station=KWLO day=2025-08-04"
    assert pairs() == ""


def test_a_line_is_a_stamp_a_level_an_event_and_the_pairs():
    text = line("info", "log.released", station="KWLO", rev=0)
    stamp, level, name, *rest = text.split(" ")
    assert level == "info"
    assert name == "log.released"
    assert rest == ["station=KWLO", "rev=0"]
    assert stamp.count(":") == 2


def test_an_event_with_no_fields_is_still_a_line():
    assert line("info", "run.started").endswith("run.started")


def test_an_event_lands_on_the_stream(caught):
    event("log.released", station="KWLO", day="2025-08-04", rev=0)
    assert len(written(caught)) == 1
    assert "log.released station=KWLO day=2025-08-04 rev=0" in caught.getvalue()


def test_the_levels_all_write(caught):
    detail("a.debug")
    event("a.info")
    warn("a.warning")
    failed("a.error")
    levels = [text.split(" ")[1] for text in written(caught)]
    assert levels == ["debug", "info", "warning", "error"]


def test_the_level_hides_what_is_below_it():
    stream = StringIO()
    configure("warning", stream)
    detail("a.debug")
    event("a.info")
    warn("a.warning")
    assert [text.split(" ")[2] for text in written(stream)] == ["a.warning"]


def test_the_level_comes_from_the_environment_when_nobody_says(monkeypatch):
    monkeypatch.setenv(LEVEL_VAR, "error")
    stream = StringIO()
    configure(stream=stream)
    warn("a.warning")
    failed("a.error")
    assert [text.split(" ")[2] for text in written(stream)] == ["a.error"]


def test_a_level_that_is_not_a_level():
    with pytest.raises(ValueError, match="is not a log level"):
        configure("chatty")


def test_configuring_twice_does_not_double_every_line():
    stream = StringIO()
    configure("info", stream)
    configure("info", stream)
    event("a.info")
    assert len(written(stream)) == 1


def test_the_package_logger_does_not_leak_into_the_root_logger():
    configure("info", StringIO())
    assert not get_logger().propagate


def test_a_traceback_is_flattened_onto_the_one_line():
    stream = StringIO()
    configure("info", stream)
    try:
        raise ValueError("the break is full")
    except ValueError:
        get_logger().error("a.failed", exc_info=True, extra={"fields": {"hour": 7}})
    text = written(stream)[0]
    assert len(written(stream)) == 1
    assert "hour=7" in text
    assert "the break is full" in text


def test_the_command_line_can_turn_it_off_for_a_block(caught):
    with quiet():
        event("a.hidden")
    event("a.shown")
    assert [text.split(" ")[2] for text in written(caught)] == ["a.shown"]


def test_turning_it_off_puts_it_back_the_way_it_was(caught):
    logger = get_logger()
    assert not logger.disabled
    with quiet():
        assert logger.disabled
    assert not logger.disabled


def test_a_named_logger_sits_under_the_package_one():
    assert get_logger("placer").name == "cartwall.placer"
    assert get_logger().name == "cartwall"


def test_the_formatter_works_on_a_record_nobody_gave_fields_to():
    record = logging.LogRecord("cartwall", logging.INFO, "f.py", 1, "a.info", None, None)
    assert Formatter().format(record).endswith("a.info")
