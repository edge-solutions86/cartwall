"""Fixture furniture shared by conftest and by tests that build their own.

Kept out of ``conftest`` so a test that needs one of these builders can import
it by name instead of asking for a fixture it does not want.
"""

from datetime import date

from cartwall.airtime import Duration
from cartwall.domain.clock import Break, BreakKind, Clock, local, music_clock, talk_clock
from cartwall.domain.programming import Assignment, ClockGrid
from cartwall.domain.ratecard import ClassOfTime, RateCard

FIXED = ClassOfTime.FIXED
NOTICE = ClassOfTime.NOTICE
IMMEDIATE = ClassOfTime.IMMEDIATE

CARD_DATE = date(2025, 1, 1)

#: A Monday, and the first day of the broadcast week 8/4 to 8/10.
MONDAY = date(2025, 8, 4)
FRIDAY = date(2025, 8, 8)
SATURDAY = date(2025, 8, 9)
SUNDAY = date(2025, 8, 10)


def farm_clock() -> Clock:
    """The 5a hour on KCNZ. Markets at :15 and :45, so the breaks sit around them."""
    return Clock.build(
        "FRM",
        "farm hour",
        [
            Break(
                position=Duration(0),
                number=1,
                kind=BreakKind.LEGAL_ID,
                length=Duration.of_seconds(10),
                units=1,
                paid=False,
            ),
            local(2, 6, 120, 4),
            local(3, 18, 180, 6),
            local(4, 33, 180, 6),
            local(5, 48, 120, 4),
        ],
    )


def a_grid(call: str, weekday_clock: str = "MUS") -> ClockGrid:
    grid = ClockGrid(call, CARD_DATE)
    grid.file_clock(music_clock())
    grid.file_clock(talk_clock())
    grid.file_clock(farm_clock())
    grid.assign(Assignment.build("all week", "M-Su", 0, 24, weekday_clock))
    return grid


def a_card(code: str, station: str, drive: int, midday: int) -> RateCard:
    return RateCard.build(
        code,
        station,
        CARD_DATE,
        [
            ("AMD", FIXED, drive),
            ("AMD", NOTICE, round(drive * 0.8)),
            ("AMD", IMMEDIATE, round(drive * 0.6)),
            ("MID", FIXED, midday),
            ("MID", NOTICE, round(midday * 0.8)),
            ("MID", IMMEDIATE, round(midday * 0.6)),
            ("PMD", FIXED, drive),
            ("PMD", NOTICE, round(drive * 0.8)),
            ("PMD", IMMEDIATE, round(drive * 0.6)),
            ("EVE", IMMEDIATE, round(midday * 0.4)),
            ("SAT", NOTICE, midday),
            ("SUN", NOTICE, round(midday * 0.8)),
            ("OVN", IMMEDIATE, 6),
            ("AAA", NOTICE, round((drive + midday) / 2)),
            ("ROS", IMMEDIATE, 12),
        ],
    )
