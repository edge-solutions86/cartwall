"""One error shape, whatever went wrong and wherever it went wrong.

Every failure that leaves this service looks the same:

    {"code": "break_full", "message": "...", "context": {...}}

The status code is a function of the error class and nothing else, which is why
the classes were given one root in the first place. A caller can switch on
``code`` and never parse a message, and the message can be rewritten without
breaking anybody.

Validation failures from the framework get folded into the same shape rather
than being let out in the framework's own format. A client that has to handle
two error shapes will handle one of them badly, and it will be the rare one.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from ..errors import CartwallError, ConflictError, LookupFailure, ValidationError

#: Which HTTP status each root maps to. Nothing else decides.
STATUS = {
    ValidationError: 422,
    LookupFailure: 404,
    ConflictError: 409,
    CartwallError: 500,
}


def status_for(failure: CartwallError) -> int:
    for kind, code in STATUS.items():
        if isinstance(failure, kind):
            return code
    return 500


def body_for(failure: CartwallError) -> dict[str, Any]:
    return failure.as_dict()


def install(app: FastAPI) -> None:
    """Teach one application to answer in the shape above."""

    @app.exception_handler(CartwallError)
    async def _domain(request: Request, failure: CartwallError) -> JSONResponse:
        return JSONResponse(status_code=status_for(failure), content=body_for(failure))

    @app.exception_handler(RequestValidationError)
    async def _framework(request: Request, failure: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=422,
            content={
                "code": "invalid",
                "message": "the request body is not the shape this route takes",
                "context": {"problems": _readable(failure.errors())},
            },
        )


def _readable(problems: Sequence[Any]) -> list[dict[str, str]]:
    """The framework's own list, trimmed to what a caller can act on."""
    return [
        {
            "where": ".".join(str(part) for part in problem.get("loc", ())),
            "why": str(problem.get("msg", "")),
        }
        for problem in problems
    ]
