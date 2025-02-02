"""Which cart runs in which spot when a line has more than one piece of copy.

A line is sold once and dubbed several times. The client has three thirties and
wants them evenly rotated, or wants the sale spot twice as often as the image
spot, or wants them strictly in order because they tell a story.

Even and weighted rotations both have to answer two questions at once, and only
one of them is arithmetic:

* Over the whole flight, does each cart get its share? That is a division and
  the largest remainder rule already solves it.
* Do the copies interleave, or does the client hear four of C1204 in a row on
  Monday and four of C1205 in a row on Tuesday? Nobody buys a rotation to get
  the second thing, and a client who hears it will call.

So the rotation is not a shuffle and it is not a counter. It is a smooth
weighted round robin: each cart carries a running position of
``(used + 1/2) / weight``, and the next spot goes to whichever cart has the
smallest one. Equal weights give a strict cycle. A two to one weight gives
A B A A B A A B, which is as close to alternating as two to one can get.

Fractions rather than floats, because the tie between two carts of equal weight
has to break the same way on every machine and in every rerun. A rotation that
depends on rounding is a rotation the log cannot be rebuilt from.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from fractions import Fraction

from ..errors import ValidationError


class RotationError(ValidationError):
    """A rotation that cannot be run."""

    code = "bad_rotation"


class RotationKind(Enum):
    EVEN = "even"
    WEIGHTED = "weighted"
    SEQUENTIAL = "sequential"

    @property
    def interleaves(self) -> bool:
        return self is not RotationKind.SEQUENTIAL


@dataclass(frozen=True, slots=True)
class Slice:
    """One cart and how much of the rotation it gets."""

    cart: str
    weight: int = 1

    def __post_init__(self) -> None:
        if not self.cart:
            raise RotationError("a rotation slice needs a cart")
        if self.weight < 1:
            raise RotationError(f"cart {self.cart} is weighted {self.weight}")

    def __str__(self) -> str:
        return self.cart if self.weight == 1 else f"{self.cart} x{self.weight}"


@dataclass(frozen=True, slots=True)
class Rotation:
    """The copy plan for one line."""

    slices: tuple[Slice, ...]
    kind: RotationKind = RotationKind.EVEN

    def __post_init__(self) -> None:
        if not self.slices:
            raise RotationError("a rotation needs at least one cart")
        carts = [piece.cart for piece in self.slices]
        repeated = {cart for cart in carts if carts.count(cart) > 1}
        if repeated:
            raise RotationError(f"{', '.join(sorted(repeated))} is in the rotation twice")
        if self.kind is RotationKind.EVEN and any(piece.weight != 1 for piece in self.slices):
            raise RotationError("an even rotation cannot carry weights")

    @classmethod
    def even(cls, *carts: str) -> Rotation:
        return cls(tuple(Slice(cart) for cart in carts), RotationKind.EVEN)

    @classmethod
    def weighted(cls, pairs: list[tuple[str, int]]) -> Rotation:
        return cls(tuple(Slice(cart, weight) for cart, weight in pairs), RotationKind.WEIGHTED)

    @classmethod
    def sequential(cls, *carts: str) -> Rotation:
        return cls(tuple(Slice(cart) for cart in carts), RotationKind.SEQUENTIAL)

    @property
    def carts(self) -> tuple[str, ...]:
        return tuple(piece.cart for piece in self.slices)

    @property
    def total_weight(self) -> int:
        return sum(piece.weight for piece in self.slices)

    def assign(self, count: int) -> list[str]:
        """Which cart runs in each of ``count`` spots, in air order."""
        if count < 0:
            raise RotationError("cannot assign a negative number of spots")
        if self.kind is RotationKind.SEQUENTIAL:
            return [self.slices[index % len(self.slices)].cart for index in range(count)]
        return self._smooth(count)

    def _smooth(self, count: int) -> list[str]:
        used = [0] * len(self.slices)
        chosen: list[str] = []
        for _ in range(count):
            best = min(
                range(len(self.slices)),
                key=lambda index: (
                    Fraction(2 * used[index] + 1, 2 * self.slices[index].weight),
                    index,
                ),
            )
            used[best] += 1
            chosen.append(self.slices[best].cart)
        return chosen

    def shares(self, count: int) -> dict[str, int]:
        """How many spots each cart ends up with over ``count``."""
        tally = dict.fromkeys(self.carts, 0)
        for cart in self.assign(count):
            tally[cart] += 1
        return tally

    def longest_run(self, count: int) -> int:
        """The most times one cart runs back to back over ``count`` spots."""
        longest = run = 0
        previous = None
        for cart in self.assign(count):
            run = run + 1 if cart == previous else 1
            previous = cart
            longest = max(longest, run)
        return longest

    def without(self, cart: str) -> Rotation:
        """Drop a cart, for when production kills one mid flight."""
        kept = tuple(piece for piece in self.slices if piece.cart != cart)
        if not kept:
            raise RotationError(f"dropping {cart} would leave the line with no copy")
        return Rotation(kept, self.kind)

    def __len__(self) -> int:
        return len(self.slices)

    def __contains__(self, cart: str) -> bool:
        return cart in self.carts

    def __str__(self) -> str:
        return f"{self.kind.value}: {', '.join(str(piece) for piece in self.slices)}"
