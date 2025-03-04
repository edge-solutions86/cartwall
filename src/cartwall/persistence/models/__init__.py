"""One module per area, because one models file becomes unreadable."""

from .reference import (  # noqa: F401
    AssignmentRow,
    BreakRow,
    ClockRow,
    DaypartRow,
    StationRow,
)
