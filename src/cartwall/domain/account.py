"""Who is buying, who is billed, and who may not sit next to whom.

Three things that get run together in conversation and have to stay apart in
the file:

* The **advertiser** is whose commercial it is. Separation rules are about
  advertisers, and so is the public file.
* The **agency** is who placed the order and who gets the fifteen percent. A
  direct advertiser has no agency and no commission.
* The **payer** is whoever the invoice goes to, which is the agency when there
  is one and the advertiser when there is not, except that some agencies buy
  on a station's paper and the advertiser is still liable. That last case is
  what ``liable_party`` is for.

Conflict codes are the part that matters to the log builder. A code is a dotted
path, ``AUTO.DOMESTIC.FORD``, and two codes conflict when one is a prefix of
the other. So the Ford dealer conflicts with the Chevrolet dealer through
``AUTO.DOMESTIC``, conflicts with the Toyota dealer through ``AUTO``, and does
not conflict with the furniture store at all. An advertiser can carry more than
one code, because the grocery store sells gasoline.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from ..money import AGENCY_RATE

CODE = re.compile(r"^[A-Z][A-Z0-9]{1,7}$")
CATEGORY = re.compile(r"^[A-Z][A-Z0-9]*(?:\.[A-Z][A-Z0-9]*)*$")


class AccountError(ValueError):
    """An account that cannot be set up as described."""


class Credit(Enum):
    """What the business office will let this account do."""

    OPEN = "open"
    PREPAY = "cash in advance"
    HOLD = "hold"

    @property
    def may_schedule(self) -> bool:
        return self is not Credit.HOLD


@dataclass(frozen=True, slots=True, order=True)
class Category:
    """A dotted conflict code. Broader codes catch narrower ones."""

    path: str

    def __post_init__(self) -> None:
        if not CATEGORY.match(self.path):
            raise AccountError(f"{self.path!r} is not a conflict code")

    @property
    def parts(self) -> tuple[str, ...]:
        return tuple(self.path.split("."))

    @property
    def depth(self) -> int:
        return len(self.parts)

    def parent(self) -> Category | None:
        if self.depth == 1:
            return None
        return Category(".".join(self.parts[:-1]))

    def covers(self, other: Category) -> bool:
        """True when ``other`` is this code or sits underneath it."""
        return other.parts[: self.depth] == self.parts

    def conflicts_with(self, other: Category) -> bool:
        return self.covers(other) or other.covers(self)

    def __str__(self) -> str:
        return self.path


@dataclass(frozen=True, slots=True)
class Agency:
    """A buying agency, with the commission it keeps."""

    code: str
    name: str
    rate: Decimal = AGENCY_RATE
    #: Some agencies place the order but the advertiser stays liable.
    advertiser_liable: bool = False

    def __post_init__(self) -> None:
        if not CODE.match(self.code):
            raise AccountError(f"{self.code!r} is not an account code")
        if not self.name:
            raise AccountError(f"agency {self.code} has no name")
        if not 0 <= self.rate < 1:
            raise AccountError(f"agency {self.code} keeps {self.rate}, which is not a commission")

    def __str__(self) -> str:
        return self.name


@dataclass(frozen=True, slots=True)
class Advertiser:
    """Whose commercial it is."""

    code: str
    name: str
    categories: frozenset[Category] = field(default_factory=frozenset)
    agency: str | None = None
    credit: Credit = Credit.OPEN
    #: Set when this account is a store of a chain that buys as one advertiser.
    parent: str | None = None

    def __post_init__(self) -> None:
        if not CODE.match(self.code):
            raise AccountError(f"{self.code!r} is not an account code")
        if not self.name:
            raise AccountError(f"advertiser {self.code} has no name")
        if self.parent == self.code:
            raise AccountError(f"{self.code} cannot be its own parent")

    @classmethod
    def build(cls, code: str, name: str, categories: str = "", **kw) -> Advertiser:
        codes = frozenset(
            Category(piece.strip()) for piece in categories.split(",") if piece.strip()
        )
        return cls(code=code, name=name, categories=codes, **kw)

    @property
    def is_direct(self) -> bool:
        return self.agency is None

    def conflicts_with(self, other: Advertiser) -> bool:
        """Two advertisers conflict when any of their codes touch."""
        if self.code == other.code:
            return True
        return any(
            mine.conflicts_with(theirs) for mine in self.categories for theirs in other.categories
        )

    def shared_categories(self, other: Advertiser) -> frozenset[Category]:
        """The broadest code each conflict runs through, for the error message."""
        found = set()
        for mine in self.categories:
            for theirs in other.categories:
                if mine.covers(theirs):
                    found.add(mine)
                elif theirs.covers(mine):
                    found.add(theirs)
        return frozenset(found)

    def __str__(self) -> str:
        return self.name


@dataclass
class AccountBook:
    """Every advertiser and agency on file."""

    advertisers: dict[str, Advertiser] = field(default_factory=dict)
    agencies: dict[str, Agency] = field(default_factory=dict)

    def add_agency(self, agency: Agency) -> None:
        if agency.code in self.agencies:
            raise AccountError(f"agency {agency.code} is already on file")
        self.agencies[agency.code] = agency

    def add_advertiser(self, advertiser: Advertiser) -> None:
        if advertiser.code in self.advertisers:
            raise AccountError(f"advertiser {advertiser.code} is already on file")
        if advertiser.agency and advertiser.agency not in self.agencies:
            raise AccountError(
                f"advertiser {advertiser.code} names agency {advertiser.agency}, "
                "which is not on file"
            )
        if advertiser.parent and advertiser.parent not in self.advertisers:
            raise AccountError(
                f"advertiser {advertiser.code} names parent {advertiser.parent}, "
                "which is not on file"
            )
        self.advertisers[advertiser.code] = advertiser

    def advertiser(self, code: str) -> Advertiser:
        try:
            return self.advertisers[code]
        except KeyError:
            raise AccountError(f"advertiser {code} is not on file") from None

    def agency(self, code: str) -> Agency:
        try:
            return self.agencies[code]
        except KeyError:
            raise AccountError(f"agency {code} is not on file") from None

    def commission_rate(self, code: str) -> Decimal:
        """What comes off this advertiser's gross. Direct business pays none."""
        advertiser = self.advertiser(code)
        if advertiser.is_direct:
            return Decimal("0")
        return self.agency(advertiser.agency or "").rate

    def liable_party(self, code: str) -> str:
        """Whose name the invoice carries."""
        advertiser = self.advertiser(code)
        if advertiser.is_direct:
            return advertiser.code
        agency = self.agency(advertiser.agency or "")
        return advertiser.code if agency.advertiser_liable else agency.code

    def billing_group(self, code: str) -> str:
        """The account the money rolls up to, walking any chain of parents."""
        advertiser = self.advertiser(code)
        seen = [advertiser.code]
        while advertiser.parent:
            if advertiser.parent in seen:
                raise AccountError(f"{' under '.join(seen)} loops back on itself")
            seen.append(advertiser.parent)
            advertiser = self.advertiser(advertiser.parent)
        return advertiser.code

    def may_schedule(self, code: str) -> bool:
        return self.advertiser(code).credit.may_schedule

    def conflicting(self, code: str) -> list[Advertiser]:
        """Everybody on file this advertiser may not sit beside, in code order."""
        subject = self.advertiser(code)
        found = [
            other
            for other in self.advertisers.values()
            if other.code != code and subject.conflicts_with(other)
        ]
        return sorted(found, key=lambda advertiser: advertiser.code)
