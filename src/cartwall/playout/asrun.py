"""Reading back what the automation says it actually played.

The as-run file is a different format from the log file and that is not an
oversight. The log format predates everybody; the as-run export was added a
decade later by different people, and it is comma separated with a header row.
Two formats is annoying and pretending they are one is worse, so they get two
modules and the differences stay visible.

What the as-run tells us that the log cannot:

* **When it really ran.** Automation drifts. A spot logged at 7:22:00 runs at
  7:22:04 because the song before it was four seconds long. Nobody cares about
  four seconds. Everybody cares about four minutes, which is what it looks like
  when a break gets pushed by a live read.
* **Whether it ran at all.** A cart that would not load is reported and the
  break carries on without it. That spot is not billable and the client is owed
  a makegood, and the only place that fact exists is this file.
* **How long it ran for.** A cut that was replaced with a shorter one gets
  reported at its real length. A thirty that ran nineteen seconds did not run.

The status column is the automation's, not ours, and its vocabulary is fixed.
Anything it sends that is not in the table is kept as ``UNKNOWN`` rather than
being dropped, because a status nobody has seen before is exactly the thing
somebody needs to look at.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import date, datetime
from enum import Enum
from io import StringIO

from ..airtime import AirTime, Duration, TimeError
from ..errors import ValidationError

#: The header the automation writes. Read, and checked, never assumed.
HEADER = ("date", "time", "cart", "title", "length", "status", "reference")


class AsRunError(ValidationError):
    """An as-run file that cannot be read."""

    code = "bad_asrun"


class Played(Enum):
    """What the automation says happened to one element."""

    PLAYED = "played"
    SKIPPED = "skipped"
    ERROR = "error"
    #: Started and cut off, usually by a hard join.
    TRUNCATED = "truncated"
    UNKNOWN = "unknown"

    @property
    def aired(self) -> bool:
        """Whether anything went out. Truncated did, and may still be billable."""
        return self in (Played.PLAYED, Played.TRUNCATED)


#: What the automation writes for each status, lower cased before lookup.
CODES = {
    "play": Played.PLAYED,
    "played": Played.PLAYED,
    "ok": Played.PLAYED,
    "skip": Played.SKIPPED,
    "skipped": Played.SKIPPED,
    "err": Played.ERROR,
    "error": Played.ERROR,
    "fail": Played.ERROR,
    "trunc": Played.TRUNCATED,
    "truncated": Played.TRUNCATED,
}


@dataclass(frozen=True, slots=True)
class AsRun:
    """One line of the as-run: what played, when, for how long."""

    day: date
    at: AirTime
    cart: str
    title: str
    length: Duration
    status: Played
    #: The order and line the log carried, when the automation echoes it back.
    reference: str = ""

    @property
    def aired(self) -> bool:
        return self.status.aired

    @property
    def key(self) -> tuple[str, int] | None:
        """The order and line, when the reference is one we wrote.

        Split on the last dash, not the first. An order number has one of its
        own, so ``25-1044-1`` partitioned forwards gives order ``25``.
        """
        order, _, line = self.reference.rpartition("-")
        if not order or not line.isdigit():
            return None
        return (order, int(line))

    def drift_from(self, scheduled: AirTime) -> Duration:
        """How far off the logged time this ran. Never negative."""
        earlier, later = sorted((self.at, scheduled))
        return later - earlier

    def __str__(self) -> str:
        return f"{self.at.twelve_hour()} {self.cart} {self.status.value}"


def _status(text: str) -> Played:
    return CODES.get(text.strip().lower(), Played.UNKNOWN)


def _length(text: str) -> Duration:
    """The automation writes seconds with one decimal, or bare tenths."""
    cleaned = text.strip()
    if not cleaned:
        raise AsRunError("an as-run line has no length")
    if "." in cleaned:
        whole, _, tenth = cleaned.partition(".")
        if not whole.isdigit() or len(tenth) != 1 or not tenth.isdigit():
            raise AsRunError(f"{text!r} is not a length")
        return Duration(int(whole) * 10 + int(tenth))
    if not cleaned.isdigit():
        raise AsRunError(f"{text!r} is not a length")
    return Duration(int(cleaned))


def parse_line(row: dict[str, str]) -> AsRun:
    # A short line comes out of the reader with None in the columns it did not
    # reach, so a missing column and an absent key are the same thing here.
    missing = [name for name in HEADER if row.get(name) is None]
    if missing:
        raise AsRunError(
            f"the as-run line is missing {', '.join(missing)}", missing=missing
        )
    try:
        day = date.fromisoformat(row["date"].strip())
    except ValueError:
        raise AsRunError(f"{row['date']!r} is not a date") from None
    try:
        at = AirTime.parse(row["time"])
    except TimeError:
        raise AsRunError(f"{row['time']!r} is not an air time") from None
    return AsRun(
        day=day,
        at=at,
        cart=row["cart"].strip(),
        title=row["title"].strip(),
        length=_length(row["length"]),
        status=_status(row["status"]),
        reference=row["reference"].strip(),
    )


def parse(text: str) -> list[AsRun]:
    """The whole file, in the order the automation wrote it."""
    reader = csv.DictReader(StringIO(text))
    if reader.fieldnames is None:
        raise AsRunError("the as-run file is empty")
    header = tuple(name.strip().lower() for name in reader.fieldnames)
    if header != HEADER:
        raise AsRunError(
            f"the as-run header is {', '.join(header)}, expected {', '.join(HEADER)}",
            header=list(header),
        )
    return [parse_line({k.strip().lower(): v for k, v in row.items()}) for row in reader]


def filename(station: str, day: date) -> str:
    if not station:
        raise AsRunError("an as-run file needs a station")
    return f"{station.replace('-', '')}{day:%y%m%d}.ASR"


def spots_only(rows: list[AsRun]) -> list[AsRun]:
    """The lines that carry one of our references. The rest is programming."""
    return [row for row in rows if row.key is not None]


def by_status(rows: list[AsRun]) -> dict[Played, int]:
    tally: dict[Played, int] = {}
    for row in rows:
        tally[row.status] = tally.get(row.status, 0) + 1
    return dict(sorted(tally.items(), key=lambda pair: pair[0].value))


def stamp(day: date, at: AirTime) -> datetime:
    """A wall clock moment, for anything that wants to sort across days."""
    return datetime(
        day.year, day.month, day.day, at.hour, at.minute, at.second
    )
