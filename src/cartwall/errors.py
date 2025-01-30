"""One root for everything this package refuses to do, and a code for each.

Up to now every module grew its own exception off ``ValueError``, which was
fine while nothing caught them. It stops being fine the moment an HTTP layer
has to turn a refusal into a status and a body, or a nightly run has to decide
whether a failure is worth waking somebody up for. A caller that has to write

    except (TimeError, SpotError, RateError, AccountError, ...)

is a caller that will miss the next one.

So: one root, three shapes underneath it, and a stable ``code`` on every leaf.
The code is part of the interface. It goes in the log line, in the JSON body
and in the operator's runbook, and it does not change when the message does.

* ``ValidationError`` means the input could not be what it claims. Also a
  ``ValueError``, so the old ``except ValueError`` around order entry keeps
  working and the constructors read the same.
* ``LookupFailure`` means we were asked for something that is not on file.
* ``ConflictError`` means the request was well formed and the state says no.
  A separation violation and a sold out break are both this.

``context`` carries the specifics that a message would otherwise bury in
prose, so a caller can pull out the cart number without parsing English.
"""

from __future__ import annotations

from typing import Any


class CartwallError(Exception):
    """Anything this package refuses on purpose."""

    code = "error"

    def __init__(self, message: str, **context: Any) -> None:
        super().__init__(message)
        self.message = message
        self.context: dict[str, Any] = context

    def as_dict(self) -> dict[str, Any]:
        return {"code": self.code, "message": self.message, "context": dict(self.context)}

    def __str__(self) -> str:
        return self.message


class ValidationError(CartwallError, ValueError):
    """The input could not be what it says it is."""

    code = "invalid"


class LookupFailure(CartwallError, KeyError):
    """Asked for something that is not on file."""

    code = "not_found"

    def __str__(self) -> str:
        # KeyError's own __str__ quotes the argument. Ours is a sentence.
        return self.message


class ConflictError(CartwallError):
    """Well formed, and the state says no."""

    code = "conflict"
