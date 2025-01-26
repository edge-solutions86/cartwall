"""Lengths that can be sold and the audio that gets played for them.

A **length** is what the order says. A **cut** is the piece of audio that runs
against it. They agree most of the time and the interesting cases are when they
do not.

Two lengths, two different limits, and they are easy to confuse:

* Seconds. A three minute break holds a hundred and eighty seconds of audio and
  not one tenth more, because the next thing after it is a song intro or a
  network join.
* Units. A break sold as six units holds six spots. A sixty takes two of them
  and a ten takes one, so six units is between six and twelve spots depending
  on what got sold, and the seconds are what decides which.

A cut's actual running time is not its sold length. Production hands over a
thirty that runs 29.4 and another that runs 30.3, and both are fine. One that
runs 32.0 is not fine, because four of those in a break push the last spot into
the network. The tolerance is half a second either way and it is checked when
the cut is filed, not when the log runs.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date

from ..airtime import Duration

#: What the rate card will quote. Anything else has to be sold as a special.
SELLABLE_SECONDS = (10, 15, 20, 30, 45, 60, 90, 120)

#: A unit is a thirty. Everything shorter still costs a whole one.
SECONDS_IN_A_UNIT = 30

#: How far a cut may run from its sold length before production has to fix it.
TOLERANCE = Duration(5)

ISCI = re.compile(r"^[A-Z]{4}[A-Z0-9]{4,8}$")
CART = re.compile(r"^[A-Z]\d{3,5}$")


class SpotError(ValueError):
    """A length or a cut that cannot be filed."""


@dataclass(frozen=True, slots=True, order=True)
class SpotLength:
    """A sold length, in whole seconds."""

    seconds: int

    def __post_init__(self) -> None:
        if self.seconds not in SELLABLE_SECONDS:
            sellable = ", ".join(f":{value}" for value in SELLABLE_SECONDS)
            raise SpotError(f":{self.seconds} is not sold here, only {sellable}")

    @classmethod
    def parse(cls, text: str) -> SpotLength:
        return cls(Duration.parse(text).whole_seconds)

    @property
    def units(self) -> int:
        """Units of inventory this takes, rounded up. A ten still costs one."""
        return -(-self.seconds // SECONDS_IN_A_UNIT)

    @property
    def air(self) -> Duration:
        return Duration.of_seconds(self.seconds)

    def fits(self, actual: Duration) -> bool:
        """Whether a cut of this running time may be filed against this length."""
        sold = self.air
        low = Duration(max(sold.tenths - TOLERANCE.tenths, 0))
        return low <= actual <= sold + TOLERANCE

    def __str__(self) -> str:
        return str(self.air)


TEN = SpotLength(10)
THIRTY = SpotLength(30)
SIXTY = SpotLength(60)


@dataclass(frozen=True, slots=True)
class Cut:
    """One piece of audio, with the dates it is allowed to run between."""

    cart: str
    isci: str
    advertiser: str
    length: SpotLength
    actual: Duration
    first_air: date
    last_air: date | None = None
    #: Pulled by the client or by production. The dates still decide what ran.
    killed: bool = False

    def __post_init__(self) -> None:
        if not CART.match(self.cart):
            raise SpotError(f"{self.cart!r} is not a cart number")
        if not ISCI.match(self.isci):
            raise SpotError(f"{self.isci!r} is not an ISCI code")
        if not self.advertiser:
            raise SpotError(f"cart {self.cart} names no advertiser")
        if not self.length.fits(self.actual):
            raise SpotError(
                f"cart {self.cart} runs {self.actual} against a {self.length} sold length"
            )
        if self.last_air and self.last_air < self.first_air:
            raise SpotError(f"cart {self.cart} is killed before it starts")

    def runs_on(self, day: date) -> bool:
        """Eligible on ``day``.

        A killed cut is not special here. Killing it set a last air date, and
        the dates are what the affidavit has to agree with. Asking whether a
        killed cut ran last Tuesday has to answer yes if it did.
        """
        if day < self.first_air:
            return False
        return self.last_air is None or day <= self.last_air

    @property
    def is_short(self) -> bool:
        return self.actual < self.length.air

    @property
    def is_long(self) -> bool:
        return self.actual > self.length.air

    def __str__(self) -> str:
        return f"{self.cart} {self.advertiser} {self.length}"


@dataclass
class CutLibrary:
    """Everything production has dubbed, live and dead."""

    cuts: dict[str, Cut] = field(default_factory=dict)

    def file(self, cut: Cut) -> None:
        if cut.cart in self.cuts:
            raise SpotError(f"cart {cut.cart} is already filed")
        self.cuts[cut.cart] = cut

    def refile(self, cut: Cut) -> None:
        """Replace a cut in place. Production redubs against the same cart."""
        if cut.cart not in self.cuts:
            raise SpotError(f"cart {cut.cart} has never been filed")
        self.cuts[cut.cart] = cut

    def cut(self, cart: str) -> Cut:
        try:
            return self.cuts[cart]
        except KeyError:
            raise SpotError(f"cart {cart} is not in the library") from None

    def kill(self, cart: str, on: date) -> Cut:
        """Stop a cut after ``on`` without losing what it was or what it ran."""
        cut = self.cut(cart)
        killed = Cut(
            cart=cut.cart,
            isci=cut.isci,
            advertiser=cut.advertiser,
            length=cut.length,
            actual=cut.actual,
            first_air=cut.first_air,
            last_air=on,
            killed=True,
        )
        self.cuts[cart] = killed
        return killed

    def running(self, day: date, advertiser: str | None = None) -> list[Cut]:
        """Cuts eligible to air on ``day``, in cart order."""
        found = [cut for cut in self.cuts.values() if cut.runs_on(day)]
        if advertiser is not None:
            found = [cut for cut in found if cut.advertiser == advertiser]
        return sorted(found, key=lambda cut: cut.cart)

    def for_advertiser(self, advertiser: str) -> list[Cut]:
        return sorted(
            (cut for cut in self.cuts.values() if cut.advertiser == advertiser),
            key=lambda cut: cut.cart,
        )

    def lengths_on_hand(self, advertiser: str, day: date) -> set[SpotLength]:
        """Which sold lengths this advertiser can actually be scheduled in."""
        return {cut.length for cut in self.running(day, advertiser)}

    def __len__(self) -> int:
        return len(self.cuts)

    def __contains__(self, cart: str) -> bool:
        return cart in self.cuts
