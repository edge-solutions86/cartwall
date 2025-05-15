"""Log lines the office can grep, and nothing that needs a parser.

The people who read these are the traffic director at six in the morning and
whoever is on call, and both of them have a terminal and no patience. So: one
line per event, a timestamp, a level, an event name, then key equals value
pairs. No JSON, no multi line tracebacks in the normal path, no colour.

    2025-08-04T05:31:02 info log.released station=KWLO day=2025-08-04 rev=0

The event name is the first thing on the line after the level, because that is
what gets grepped. It is dotted, lower case, and it is an interface: renaming
one breaks somebody's saved search and their alerting, so it is done on purpose
or not at all.

Values are written so a value with a space in it does not split a field. That
is the only cleverness here and it exists because an advertiser is called
"Black Hawk Bank".
"""

from __future__ import annotations

import logging
import os
import sys
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import datetime
from typing import Any

ROOT = "cartwall"

#: The environment variable that turns the level up.
LEVEL_VAR = "CARTWALL_LOG_LEVEL"

LEVELS = {
    "debug": logging.DEBUG,
    "info": logging.INFO,
    "warning": logging.WARNING,
    "error": logging.ERROR,
}


def render(value: Any) -> str:
    """One value, written so it cannot split a field."""
    if value is None:
        return "-"
    if isinstance(value, bool):
        return "yes" if value else "no"
    text = str(value)
    if not text:
        return '""'
    if any(character.isspace() for character in text) or '"' in text:
        return '"' + text.replace('"', "'") + '"'
    return text


def pairs(**fields: Any) -> str:
    """Key equals value, in the order they were passed."""
    return " ".join(f"{name}={render(value)}" for name, value in fields.items())


def line(level: str, event: str, **fields: Any) -> str:
    """The whole line, without the newline."""
    stamp = datetime.now().strftime("%Y-%m-%dT%H:%M:%S")
    tail = pairs(**fields)
    return f"{stamp} {level} {event}" + (f" {tail}" if tail else "")


class Formatter(logging.Formatter):
    """Puts the house format on whatever the standard library hands over."""

    def format(self, record: logging.LogRecord) -> str:
        fields = getattr(record, "fields", {}) or {}
        text = line(record.levelname.lower(), record.getMessage(), **fields)
        if record.exc_info:
            text += " " + pairs(error=self.formatException(record.exc_info).splitlines()[-1])
        return text


def get_logger(name: str = "") -> logging.Logger:
    return logging.getLogger(f"{ROOT}.{name}" if name else ROOT)


def configure(level: str | None = None, stream=None) -> logging.Logger:
    """Point the package's logger at one handler. Safe to call twice."""
    asked = level if level is not None else os.environ.get(LEVEL_VAR) or "info"
    wanted = asked.lower()
    if wanted not in LEVELS:
        raise ValueError(f"{wanted!r} is not a log level")
    logger = get_logger()
    logger.setLevel(LEVELS[wanted])
    logger.propagate = False
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.setFormatter(Formatter())
    logger.addHandler(handler)
    return logger


def event(name: str, **fields: Any) -> None:
    """The ordinary case. One thing happened and here is what it was."""
    get_logger().info(name, extra={"fields": fields})


def warn(name: str, **fields: Any) -> None:
    get_logger().warning(name, extra={"fields": fields})


def failed(name: str, **fields: Any) -> None:
    get_logger().error(name, extra={"fields": fields})


def detail(name: str, **fields: Any) -> None:
    get_logger().debug(name, extra={"fields": fields})


@contextmanager
def quiet() -> Iterator[None]:
    """Turn the package's logging off for the length of a block.

    For the command line, which prints its own output and does not want the
    same facts twice.
    """
    logger = get_logger()
    was = logger.disabled
    logger.disabled = True
    try:
        yield
    finally:
        logger.disabled = was
