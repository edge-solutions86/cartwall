"""Handing the log to the automation, in the format it will actually read.

The automation takes a flat text file, one line per element, fixed width, no
header and no footer. It is not a good format and it is not negotiable: the
playout system has read it since before any of us arrived and it will read it
after we leave.

Three things about it that have cost somebody an evening:

* **The hour is repeated on every line.** The automation loads an hour at a
  time and does not carry state between them, so a line that says only ``:22``
  is a line it will put in whatever hour it happens to be loading.
* **Lengths are in tenths, written without the point.** ``300`` is a thirty.
  A file that writes ``30.0`` loads as a three second spot, and the operator
  finds out during morning drive.
* **The order of the columns is the order of the columns.** There is no header
  to disagree with, so a column added in the middle silently shifts everything
  after it. The width table below is the interface.

The file is written in air order and nothing sorts it afterwards.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ..airtime import Duration
from ..errors import ValidationError
from ..logs.day import DayLog, Element

#: Column widths, in order. Changing one of these is a change to the interface.
WIDTHS = (
    ("hour", 2),
    ("start", 8),
    ("kind", 3),
    ("tenths", 5),
    ("cart", 8),
    ("order", 12),
    ("line", 3),
    ("advertiser", 20),
)

LINE_WIDTH = sum(width for _, width in WIDTHS) + len(WIDTHS) - 1

#: What the automation calls each kind of element.
KINDS = {
    Element.SPOT: "SPT",
    Element.LEGAL_ID: "LID",
    Element.NETWORK: "NET",
    Element.PROMO: "PRO",
}


class ExportError(ValidationError):
    """A log that cannot be written in the automation's format."""

    code = "bad_export"


@dataclass(frozen=True, slots=True)
class Row:
    """One line of the file, before it is padded out."""

    hour: int
    start: str
    kind: str
    tenths: int
    cart: str
    order: str
    line: int
    advertiser: str

    def render(self) -> str:
        values = {
            "hour": f"{self.hour:02d}",
            "start": self.start,
            "kind": self.kind,
            "tenths": f"{self.tenths:05d}",
            "cart": self.cart,
            "order": self.order,
            "line": f"{self.line:03d}" if self.line else "000",
            "advertiser": self.advertiser,
        }
        pieces = []
        for name, width in WIDTHS:
            value = values[name][:width]
            pieces.append(value.ljust(width))
        return " ".join(pieces).rstrip()


def rows_for(log: DayLog) -> list[Row]:
    """Every element the automation is handed, in air order. Holes are not."""
    rows = []
    for entry in log.airable():
        kind = KINDS.get(entry.element)
        if kind is None:
            raise ExportError(
                f"{entry.element.value} has no code in the automation's format",
                element=entry.element.value,
            )
        rows.append(
            Row(
                hour=entry.hour,
                start=str(entry.at),
                kind=kind,
                tenths=entry.length.tenths,
                cart=entry.cart,
                order=entry.order,
                line=entry.line,
                advertiser=entry.advertiser,
            )
        )
    return rows


def render(log: DayLog) -> str:
    """The whole file, newline separated, with a trailing newline."""
    return "".join(row.render() + "\n" for row in rows_for(log))


def filename(station: str, day: date) -> str:
    """What the automation expects to find in the watch folder."""
    if not station:
        raise ExportError("a log file needs a station")
    return f"{station.replace('-', '')}{day:%y%m%d}.LOG"


def parse_row(text: str) -> Row:
    """Read one line back. Used by the tests and by the import round trip."""
    if not text.strip():
        raise ExportError("a blank line is not a log line")
    at = 0
    values: dict[str, str] = {}
    for name, width in WIDTHS:
        values[name] = text[at : at + width].strip()
        at += width + 1
    try:
        return Row(
            hour=int(values["hour"]),
            start=values["start"],
            kind=values["kind"],
            tenths=int(values["tenths"]),
            cart=values["cart"],
            order=values["order"],
            line=int(values["line"] or 0),
            advertiser=values["advertiser"],
        )
    except ValueError:
        raise ExportError(f"{text!r} is not a log line") from None


def parse(text: str) -> list[Row]:
    return [parse_row(line) for line in text.splitlines() if line.strip()]


def length_of(tenths: int) -> Duration:
    return Duration(tenths)
