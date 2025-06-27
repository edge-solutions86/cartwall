"""Rebuilding the house out of what is on file, for whoever is asking.

The rule book gathers one station's tables. The house gathers the cluster's,
and both the HTTP layer and the command line need it before they can do
anything at all. It was written twice, once in each, which is how the two
quietly stopped agreeing about which grid was in force.

So: one function, here, and neither caller keeps its own.

It is rebuilt per request rather than cached. That is a real decision and it
goes the way it does because the alternative is worse: a cached house is a
house that is one rate card behind on the day somebody files a new one, and
nobody notices until an invoice is wrong. Reading six stations' tables is a
handful of small queries against tables with tens of rows.

The grid in force is the one with the latest effective date not in the future.
A grid filed for next month is on file and is not yet running.
"""

from __future__ import annotations

from datetime import date

from sqlalchemy import select

from ..domain.programming import ClockGrid
from ..domain.rulebook import House
from ..domain.separation import SeparationBook, house_rules
from ..errors import LookupFailure
from ..persistence.models.reference import AssignmentRow
from ..persistence.unitofwork import UnitOfWork


def grid_in_force(uow: UnitOfWork, call: str, on: date | None = None) -> ClockGrid:
    """The newest grid for ``call`` that had taken effect by ``on``."""
    query = select(AssignmentRow.effective).where(AssignmentRow.station == call)
    if on is not None:
        query = query.where(AssignmentRow.effective <= on)
    effective = uow.session.scalars(query.order_by(AssignmentRow.effective.desc())).first()
    if effective is None:
        raise LookupFailure(f"{call} has no programming grid on file", call=call)
    return uow.stations.grid(call, effective)


def separation_for(uow: UnitOfWork, call: str) -> SeparationBook:
    """The rules one station runs.

    Every station runs the house book at the moment. When a station wants its
    own, this is the one place that has to change.
    """
    return house_rules()


def house_from(uow: UnitOfWork, on: date | None = None) -> House:
    """Everything the cluster has filed, as of ``on``."""
    cluster = uow.stations.cluster()
    calls = [station.call for station in cluster.logged]
    return House(
        cluster=cluster,
        accounts=uow.accounts.book(),
        cards=uow.cards.book(),
        dayparts={call: uow.stations.dayparts(call) for call in calls},
        programming={call: grid_in_force(uow, call, on) for call in calls},
        separation={call: separation_for(uow, call) for call in calls},
    )


def grid_for(uow: UnitOfWork, station: str, on: date | None = None) -> ClockGrid:
    """The grid a spot bought on ``station`` is actually scheduled against."""
    cluster = uow.stations.cluster()
    return grid_in_force(uow, cluster.logging_station(station).call, on)
