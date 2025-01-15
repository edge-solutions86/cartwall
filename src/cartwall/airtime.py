"""Clock positions and lengths, in tenths of a second.

Two types, and the reason they are separate is worth stating once. An
``AirTime`` is a position in the broadcast day. A ``Duration`` is a length. You
can add a length to a position and get a position, and you can subtract two
positions and get a length, but adding two positions is meaningless and the
type system should say so.

Everything is held as an integer number of tenths. Cut lengths off the
automation come back as ``29.7`` and ``30.2``, floats accumulate error over a
four hour music log, and a break that is two tenths over runs into the network
join. Integers do not drift.

The broadcast day runs midnight to midnight and an ``AirTime`` cannot leave it.
Overnight programming that crosses the boundary belongs to the next day's log,
which is what the automation does and what the affidavit has to agree with.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

TENTHS_PER_SECOND = 10
SECONDS_PER_MINUTE = 60
SECONDS_PER_HOUR = 3600
SECONDS_PER_DAY = 86400
TENTHS_PER_DAY = SECONDS_PER_DAY * TENTHS_PER_SECOND

_DURATION = re.compile(r"^(?:(\d+):)?(?:(\d+):)?(\d+)(?:\.(\d))?$")
_AIRTIME = re.compile(r"^(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.(\d))?\s*([ap])?$", re.IGNORECASE)


class TimeError(ValueError):
    """A time or a length that cannot be what it says it is."""


@dataclass(frozen=True, slots=True, order=True)
class Duration:
    """A length of air, in tenths of a second. Never negative."""

    tenths: int

    def __post_init__(self) -> None:
        if not isinstance(self.tenths, int):
            raise TimeError(f"a duration is a whole number of tenths, not {self.tenths!r}")
        if self.tenths < 0:
            raise TimeError("a duration cannot run backwards")

    @classmethod
    def of_seconds(cls, seconds: int) -> Duration:
        return cls(seconds * TENTHS_PER_SECOND)

    @classmethod
    def parse(cls, text: str) -> Duration:
        """``:30``, ``30``, ``1:00``, ``1:00.5`` and ``00:01:30`` all work.

        The leading colon is how the sales side writes a thirty, and it means
        the same thing as a bare thirty.
        """
        cleaned = text.strip().lstrip(":")
        match = _DURATION.match(cleaned)
        if not match:
            raise TimeError(f"{text!r} is not a length")
        first, second, last, tenth = match.groups()
        parts = [part for part in (first, second, last) if part is not None]
        seconds = 0
        for part in parts:
            seconds = seconds * SECONDS_PER_MINUTE + int(part)
        return cls(seconds * TENTHS_PER_SECOND + int(tenth or 0))

    @property
    def seconds(self) -> float:
        return self.tenths / TENTHS_PER_SECOND

    @property
    def whole_seconds(self) -> int:
        """Rounded up. A cut that runs 30.1 occupies 31 seconds of a break."""
        return -(-self.tenths // TENTHS_PER_SECOND)

    def __add__(self, other: Duration) -> Duration:
        return Duration(self.tenths + other.tenths)

    def __sub__(self, other: Duration) -> Duration:
        return Duration(self.tenths - other.tenths)

    def __mul__(self, count: int) -> Duration:
        return Duration(self.tenths * count)

    __rmul__ = __mul__

    def __bool__(self) -> bool:
        return self.tenths != 0

    def __str__(self) -> str:
        total, tenth = divmod(self.tenths, TENTHS_PER_SECOND)
        minutes, seconds = divmod(total, SECONDS_PER_MINUTE)
        hours, minutes = divmod(minutes, SECONDS_PER_MINUTE)
        fraction = f".{tenth}" if tenth else ""
        if hours:
            return f"{hours}:{minutes:02d}:{seconds:02d}{fraction}"
        if minutes:
            return f"{minutes}:{seconds:02d}{fraction}"
        return f":{seconds:02d}{fraction}"


ZERO = Duration(0)


@dataclass(frozen=True, slots=True, order=True)
class AirTime:
    """A position in the broadcast day, in tenths from midnight."""

    tenths: int

    def __post_init__(self) -> None:
        if not isinstance(self.tenths, int):
            raise TimeError(f"an air time is a whole number of tenths, not {self.tenths!r}")
        if not 0 <= self.tenths < TENTHS_PER_DAY:
            raise TimeError(f"{self.tenths} tenths falls outside the broadcast day")

    @classmethod
    def at(cls, hour: int, minute: int = 0, second: int = 0) -> AirTime:
        if not 0 <= hour < 24:
            raise TimeError(f"hour {hour} is not in the broadcast day")
        if not 0 <= minute < 60 or not 0 <= second < 60:
            raise TimeError(f"{hour}:{minute}:{second} is not a clock time")
        total = hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second
        return cls(total * TENTHS_PER_SECOND)

    @classmethod
    def parse(cls, text: str) -> AirTime:
        """``6:00a``, ``2:15:30p`` and ``14:15:30`` are all the same shape.

        Twelve hour with a trailing a or p is what the log prints and what the
        traffic director types. Twenty four hour is what the automation sends.
        """
        match = _AIRTIME.match(text.strip())
        if not match:
            raise TimeError(f"{text!r} is not an air time")
        written, minute, second, tenth, meridiem = match.groups()
        hour = int(written)
        if meridiem:
            if not 1 <= hour <= 12:
                raise TimeError(f"{text!r} is not a twelve hour clock time")
            hour = hour % 12
            if meridiem.lower() == "p":
                hour += 12
        base = cls.at(hour, int(minute), int(second or 0))
        return cls(base.tenths + int(tenth or 0))

    @property
    def hour(self) -> int:
        return self.tenths // (SECONDS_PER_HOUR * TENTHS_PER_SECOND)

    @property
    def minute(self) -> int:
        return self.tenths // (SECONDS_PER_MINUTE * TENTHS_PER_SECOND) % SECONDS_PER_MINUTE

    @property
    def second(self) -> int:
        return self.tenths // TENTHS_PER_SECOND % SECONDS_PER_MINUTE

    @property
    def top_of_hour(self) -> AirTime:
        return AirTime.at(self.hour)

    @property
    def into_hour(self) -> Duration:
        """How far past the top of the hour this sits."""
        return Duration(self.tenths - self.top_of_hour.tenths)

    def __add__(self, length: Duration) -> AirTime:
        end = self.tenths + length.tenths
        if end >= TENTHS_PER_DAY:
            raise TimeError("that runs past midnight, which belongs to the next log")
        return AirTime(end)

    def __sub__(self, other: Duration | AirTime) -> AirTime | Duration:
        if isinstance(other, Duration):
            start = self.tenths - other.tenths
            if start < 0:
                raise TimeError("that runs back before midnight")
            return AirTime(start)
        return Duration(self.tenths - other.tenths)

    def twelve_hour(self) -> str:
        hour = self.hour % 12 or 12
        meridiem = "a" if self.hour < 12 else "p"
        return f"{hour}:{self.minute:02d}:{self.second:02d}{meridiem}"

    def __str__(self) -> str:
        tenth = self.tenths % TENTHS_PER_SECOND
        fraction = f".{tenth}" if tenth else ""
        return f"{self.hour:02d}:{self.minute:02d}:{self.second:02d}{fraction}"


MIDNIGHT = AirTime(0)


def total_length(lengths: list[Duration]) -> Duration:
    """Sum, because ``sum`` wants a zero of the wrong type."""
    return Duration(sum(length.tenths for length in lengths))


def backtime(join_at: AirTime, lengths: list[Duration]) -> list[AirTime]:
    """Start times for a run of elements that has to end exactly on ``join_at``.

    Continuity backtimes the last break of an hour off the network join rather
    than forward off the last song, because the join is the one time in the
    hour that cannot move.
    """
    starts: list[AirTime] = []
    cursor = join_at
    for length in reversed(lengths):
        cursor = cursor - length
        starts.append(cursor)
    starts.reverse()
    return starts
