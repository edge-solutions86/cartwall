"""Money in whole cents, and the fifteen percent that never quite reverses.

Every amount in the system is an integer number of cents. No floats reach a
statement. A rate card quotes dollars, an invoice adds up thousands of spots,
and a tenth of a cent repeated four thousand times is a dollar the agency will
short pay over.

The commission is the part worth reading twice. An agency buys at net and the
station books at gross, and the two are related by fifteen percent of the
gross, not by fifteen percent of the net:

    net = gross - 15% of gross = gross * 0.85

Going the other way divides, and division does not land on a cent. A net of
$100.00 grosses back up to $117.65, rounded up from 117.6470 and change.

Netting down is the lossy direction, which is the opposite of what people
expect. Multiplying by 0.85 squeezes a hundred cents of gross into eighty five
cents of net, so different grosses land on the same net and cannot be told
apart afterwards. A gross of $0.23 nets to $0.20 and grosses back up to $0.24.
About one gross in seven does not come home. ``round_trips`` says whether a
particular one does, and order entry uses it to warn the seller rather than
book a gross that will not settle against the agency's own paperwork.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal

CENTS = Decimal("0.01")

#: What a recognised agency keeps. Direct business has no commission.
AGENCY_RATE = Decimal("0.15")

_AMOUNT = re.compile(r"^\$?\s*(-?)([\d,]+)(?:\.(\d{1,2}))?$")


class MoneyError(ValueError):
    """An amount that is not an amount."""


def _round(value: Decimal) -> int:
    """Decimal dollars to whole cents, half up, the way an invoice rounds."""
    return int((value * 100).quantize(Decimal(1), rounding=ROUND_HALF_UP))


@dataclass(frozen=True, slots=True, order=True)
class Money:
    """Whole cents. Negative is allowed, because credits exist."""

    cents: int

    def __post_init__(self) -> None:
        if not isinstance(self.cents, int):
            raise MoneyError(f"money is whole cents, not {self.cents!r}")

    @classmethod
    def dollars(cls, amount: Decimal | int | str) -> Money:
        return cls(_round(Decimal(amount)))

    @classmethod
    def parse(cls, text: str) -> Money:
        """``$1,250``, ``1250.00`` and ``-45.50`` are all amounts."""
        match = _AMOUNT.match(text.strip())
        if not match:
            raise MoneyError(f"{text!r} is not an amount")
        sign, whole, fraction = match.groups()
        cents = int(whole.replace(",", "")) * 100 + int((fraction or "0").ljust(2, "0"))
        return cls(-cents if sign else cents)

    @property
    def amount(self) -> Decimal:
        return (Decimal(self.cents) / 100).quantize(CENTS)

    def __add__(self, other: Money) -> Money:
        return Money(self.cents + other.cents)

    def __sub__(self, other: Money) -> Money:
        return Money(self.cents - other.cents)

    def __neg__(self) -> Money:
        return Money(-self.cents)

    def __abs__(self) -> Money:
        return Money(abs(self.cents))

    def __mul__(self, count: int) -> Money:
        if not isinstance(count, int):
            raise MoneyError("money multiplies by a count, not by a rate")
        return Money(self.cents * count)

    __rmul__ = __mul__

    def __bool__(self) -> bool:
        return self.cents != 0

    def scale(self, factor: Decimal) -> Money:
        """A percentage of an amount, rounded to the cent, half up."""
        return Money(_round(self.amount * Decimal(factor)))

    def allocate(self, weights: list[int]) -> list[Money]:
        """Split this amount by ``weights`` so the pieces add back to it exactly.

        Largest remainder. Give everybody the floor of their share, then hand
        the leftover cents out one at a time to whoever was robbed most. A
        four week flight of $1,000.00 does not divide by three markets, and
        somebody has to be a cent light rather than the invoice being a cent
        wrong.
        """
        if not weights:
            raise MoneyError("nothing to allocate to")
        if any(weight < 0 for weight in weights):
            raise MoneyError("a share cannot be negative")
        total = sum(weights)
        if total == 0:
            raise MoneyError("the shares are all zero")
        floors = [self.cents * weight // total for weight in weights]
        remainder = self.cents - sum(floors)
        order = sorted(
            range(len(weights)),
            key=lambda index: (-(self.cents * weights[index] % total), index),
        )
        step = 1 if remainder >= 0 else -1
        for position in range(abs(remainder)):
            floors[order[position % len(order)]] += step
        return [Money(cents) for cents in floors]

    def split(self, ways: int) -> list[Money]:
        """Even split, which is allocate with equal weights."""
        if ways < 1:
            raise MoneyError("cannot split fewer than one way")
        return self.allocate([1] * ways)

    def __str__(self) -> str:
        sign = "-" if self.cents < 0 else ""
        whole, cents = divmod(abs(self.cents), 100)
        return f"{sign}${whole:,}.{cents:02d}"

    def __repr__(self) -> str:
        return f"Money.parse({str(self)!r})"


ZERO = Money(0)


def total(amounts: list[Money]) -> Money:
    return Money(sum(amount.cents for amount in amounts))


def commission(gross: Money, rate: Decimal = AGENCY_RATE) -> Money:
    """What the agency keeps out of a gross amount."""
    return gross.scale(rate)


def to_net(gross: Money, rate: Decimal = AGENCY_RATE) -> Money:
    """Gross less commission. This is the direction that is exact."""
    return gross - commission(gross, rate)


def to_gross(net: Money, rate: Decimal = AGENCY_RATE) -> Money:
    """Gross the net back up. Rounded, so it is not always reversible."""
    if rate >= 1:
        raise MoneyError("a commission of everything cannot be grossed up")
    return Money(_round(net.amount / (Decimal(1) - Decimal(rate))))


def round_trips(gross: Money, rate: Decimal = AGENCY_RATE) -> bool:
    """Whether netting down and grossing back up lands on the same cent."""
    return to_gross(to_net(gross, rate), rate) == gross
