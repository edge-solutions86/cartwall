"""Who is calling, and what that lets them do.

Six people use this and two of them are scripts, so the authentication is an
API key in a header and there is no session, no login page and no password
reset. What there is, and what matters, is that a key carries **a person and a
role** rather than merely existing.

The person is the point. Every write ends up in the audit trail with a name
against it, and a key that only says "authorised" gives the trail nothing to
record. A shared key with no name attached is how an audit becomes a shrug.

Four roles, and they are a ladder rather than a set of flags:

* **reader** can see everything and change nothing. The sales laptops.
* **seller** can write and confirm orders. Cannot touch a log.
* **traffic** can do all of that and build, sign and release logs.
* **manager** can do all of that and run the billing.

A ladder because the alternative is a permission matrix that nobody keeps
correct, and because the office genuinely works this way: the traffic director
does everything a seller does and more.

Keys are hashed, never stored in the clear, and compared in constant time. That
is more care than the threat model needs and it costs four lines.
"""

from __future__ import annotations

import hashlib
import hmac
import os
from dataclasses import dataclass
from enum import IntEnum

from fastapi import Depends, Request
from fastapi.security import APIKeyHeader

from ..errors import CartwallError

HEADER = "X-Cartwall-Key"

#: Where the keys come from. ``name:role:key`` entries, semicolon separated.
KEYS_VAR = "CARTWALL_API_KEYS"


class NotAllowed(CartwallError):
    """A caller who may not do this."""

    code = "not_allowed"


class NotAuthenticated(CartwallError):
    """A caller we do not recognise."""

    code = "not_authenticated"


class Role(IntEnum):
    """A ladder. Higher can do everything lower can."""

    READER = 1
    SELLER = 2
    TRAFFIC = 3
    MANAGER = 4

    @classmethod
    def named(cls, text: str) -> Role:
        try:
            return cls[text.strip().upper()]
        except KeyError:
            raise NotAuthenticated(f"{text!r} is not a role", role=text) from None

    @property
    def label(self) -> str:
        return self.name.lower()

    def can(self, wanted: Role) -> bool:
        return self >= wanted


@dataclass(frozen=True, slots=True)
class Caller:
    """The person behind a request, for the audit trail as much as the gate."""

    name: str
    role: Role

    def require(self, wanted: Role) -> None:
        if not self.role.can(wanted):
            raise NotAllowed(
                f"{self.name} is {self.role.label} and this needs {wanted.label}",
                actor=self.name,
                has=self.role.label,
                needs=wanted.label,
            )

    def __str__(self) -> str:
        return f"{self.name} ({self.role.label})"


def digest(key: str) -> str:
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


@dataclass
class KeyRing:
    """Every key the process will accept, by hash."""

    keys: dict[str, Caller]

    @classmethod
    def parse(cls, text: str) -> KeyRing:
        """``dholm:seller:secret;tronan:traffic:other``."""
        keys: dict[str, Caller] = {}
        for entry in text.split(";"):
            if not entry.strip():
                continue
            parts = entry.split(":")
            if len(parts) != 3:
                raise NotAuthenticated(
                    f"{entry.strip()!r} is not a name, a role and a key", entry=entry
                )
            name, role, key = (part.strip() for part in parts)
            if not name or not key:
                raise NotAuthenticated(f"{entry.strip()!r} has no name or no key")
            keys[digest(key)] = Caller(name, Role.named(role))
        return cls(keys)

    @classmethod
    def from_environment(cls) -> KeyRing:
        return cls.parse(os.environ.get(KEYS_VAR, ""))

    def caller(self, key: str | None) -> Caller:
        if not key:
            raise NotAuthenticated(f"no {HEADER} header on the request")
        offered = digest(key)
        for known, caller in self.keys.items():
            if hmac.compare_digest(known, offered):
                return caller
        raise NotAuthenticated("that key is not one of ours")

    @property
    def is_empty(self) -> bool:
        return not self.keys

    def __len__(self) -> int:
        return len(self.keys)


header = APIKeyHeader(name=HEADER, auto_error=False)


def get_caller(request: Request, key: str | None = Depends(header)) -> Caller:
    """Who is calling. Refuses when the ring is empty rather than waving through."""
    ring: KeyRing = request.app.state.keys
    if ring.is_empty:
        raise NotAuthenticated(
            "no keys are configured, so nobody can be let in",
            hint=f"set {KEYS_VAR}",
        )
    return ring.caller(key)


def needs(role: Role):
    """A dependency that lets a caller through only at ``role`` or above."""

    def gate(caller: Caller = Depends(get_caller)) -> Caller:
        caller.require(role)
        return caller

    return Depends(gate)


Reader = needs(Role.READER)
Seller = needs(Role.SELLER)
Traffic = needs(Role.TRAFFIC)
Manager = needs(Role.MANAGER)
