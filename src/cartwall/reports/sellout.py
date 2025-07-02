"""How full the station is, and how it compares with where it should be.

Two questions that sound like one and are not.

**Sellout** is a fact about a day: units sold over units available. It is what
the traffic director reads and it is honest as far as it goes.

**Pacing** is sellout compared with something. Against last year, which is what
the general manager asks. Against budget, which is what the owner asks. Against
the same point in the previous month, which is what the sales manager asks
because it is the only one they can still do anything about.

The trap in all three is the denominator. A week with a ballgame in it has
fewer units than the week before, so raw sold counts fall while sellout rises,
and reporting the first as if it were the second makes a good week look bad.
Every figure here carries its own denominator for that reason, and pacing
against a period with a different amount of inventory says so rather than
quietly comparing percentages.

Money is at the rate the spots were sold at, not at card. A week that filled up
on run of schedule at twelve dollars is not the same week as one that filled up
on drive at sixty, and a report that only counts units cannot tell them apart.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from ..errors import ValidationError
from ..money import ZERO, Money


class SelloutError(ValidationError):
    """A comparison that cannot be made."""

    code = "bad_sellout"


@dataclass(frozen=True, slots=True)
class Period:
    """What one span came to. The denominator travels with the number."""

    label: str
    first: date
    last: date
    units: int
    sold: int
    revenue: Money = ZERO

    def __post_init__(self) -> None:
        if self.last < self.first:
            raise SelloutError(f"{self.label} runs backwards")
        if self.sold > self.units:
            raise SelloutError(
                f"{self.label} sold {self.sold} of {self.units}, which is oversold",
                label=self.label,
                sold=self.sold,
                units=self.units,
            )

    @property
    def open(self) -> int:
        return self.units - self.sold

    @property
    def sellout(self) -> float:
        if not self.units:
            return 0.0
        return self.sold / self.units

    @property
    def rate(self) -> Money:
        """What the average sold unit went for."""
        if not self.sold:
            return ZERO
        return Money(self.revenue.cents // self.sold)

    def __str__(self) -> str:
        return (
            f"{self.label:<14} {self.sold:>6} of {self.units:<6} "
            f"{self.sellout:>6.1%}  {self.revenue}"
        )


@dataclass(frozen=True, slots=True)
class Pace:
    """One period measured against another."""

    now: Period
    then: Period

    @property
    def unit_change(self) -> int:
        return self.now.sold - self.then.sold

    @property
    def revenue_change(self) -> Money:
        return self.now.revenue - self.then.revenue

    @property
    def sellout_change(self) -> float:
        return self.now.sellout - self.then.sellout

    @property
    def comparable(self) -> bool:
        """Whether the two periods had the same amount of inventory."""
        return self.now.units == self.then.units

    @property
    def index(self) -> float | None:
        """Revenue now as a fraction of revenue then. None when then was nil."""
        if not self.then.revenue.cents:
            return None
        return self.now.revenue.cents / self.then.revenue.cents

    def __str__(self) -> str:
        note = "" if self.comparable else "  (different inventory)"
        return (
            f"{self.now.label} against {self.then.label}: "
            f"{self.unit_change:+d} units, {self.revenue_change} "
            f"{self.sellout_change:+.1%}{note}"
        )


def pace(now: Period, then: Period) -> Pace:
    return Pace(now, then)


def against_budget(now: Period, budget: Money) -> tuple[Money, float | None]:
    """How far off budget the period came, and by what fraction."""
    gap = now.revenue - budget
    if not budget.cents:
        return gap, None
    return gap, now.revenue.cents / budget.cents


def busiest(periods: list[Period]) -> Period | None:
    """The fullest period, by sellout rather than by units."""
    with_units = [period for period in periods if period.units]
    if not with_units:
        return None
    return max(with_units, key=lambda period: (period.sellout, period.revenue.cents))


def thinnest(periods: list[Period]) -> Period | None:
    with_units = [period for period in periods if period.units]
    if not with_units:
        return None
    return min(with_units, key=lambda period: (period.sellout, period.revenue.cents))


def rolled_up(periods: list[Period], label: str) -> Period:
    """Several periods as one. Refuses to roll up an empty list."""
    if not periods:
        raise SelloutError("nothing to roll up")
    return Period(
        label=label,
        first=min(period.first for period in periods),
        last=max(period.last for period in periods),
        units=sum(period.units for period in periods),
        sold=sum(period.sold for period in periods),
        revenue=Money(sum(period.revenue.cents for period in periods)),
    )
