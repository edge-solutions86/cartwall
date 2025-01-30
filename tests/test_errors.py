"""The error tree is an interface. These tests are what keeps it one."""

import pytest

from cartwall.airtime import TimeError
from cartwall.domain.account import AccountError
from cartwall.domain.clock import ClockError
from cartwall.domain.daypart import DaypartError
from cartwall.domain.ratecard import RateError
from cartwall.domain.spot import SpotError
from cartwall.domain.station import StationError
from cartwall.errors import (
    CartwallError,
    ConflictError,
    LookupFailure,
    ValidationError,
)
from cartwall.money import MoneyError

LEAVES = [
    TimeError,
    MoneyError,
    StationError,
    DaypartError,
    ClockError,
    SpotError,
    AccountError,
    RateError,
]


@pytest.mark.parametrize("leaf", LEAVES, ids=lambda leaf: leaf.__name__)
def test_every_refusal_in_the_package_has_one_root(leaf):
    assert issubclass(leaf, CartwallError)


@pytest.mark.parametrize("leaf", LEAVES, ids=lambda leaf: leaf.__name__)
def test_every_leaf_carries_its_own_code(leaf):
    assert leaf.code != CartwallError.code
    assert leaf.code != ValidationError.code


def test_no_two_leaves_share_a_code():
    codes = [leaf.code for leaf in LEAVES]
    assert len(set(codes)) == len(codes)


def test_the_old_except_value_error_around_order_entry_still_catches_everything():
    for leaf in LEAVES:
        assert issubclass(leaf, ValueError)


def test_a_lookup_failure_is_a_key_error_so_dict_style_callers_still_work():
    assert issubclass(LookupFailure, KeyError)
    with pytest.raises(KeyError):
        raise LookupFailure("nothing on file")


def test_a_lookup_failure_prints_a_sentence_and_not_a_quoted_argument():
    # KeyError's own __str__ would give back "'nothing on file'" with quotes.
    assert str(LookupFailure("nothing on file")) == "nothing on file"


def test_a_conflict_is_not_a_validation_failure():
    assert not issubclass(ConflictError, ValidationError)
    assert not issubclass(ConflictError, ValueError)
    assert issubclass(ConflictError, CartwallError)


def test_an_error_carries_the_specifics_beside_the_prose():
    failure = SpotError("cart C1204 runs :32 against a :30", cart="C1204", ran="32.0")
    assert failure.message == "cart C1204 runs :32 against a :30"
    assert failure.context == {"cart": "C1204", "ran": "32.0"}
    assert str(failure) == "cart C1204 runs :32 against a :30"


def test_an_error_hands_the_http_layer_something_it_can_serialise():
    failure = AccountError("advertiser NOPE is not on file", advertiser="NOPE")
    assert failure.as_dict() == {
        "code": "bad_account",
        "message": "advertiser NOPE is not on file",
        "context": {"advertiser": "NOPE"},
    }


def test_the_context_is_a_copy_so_a_caller_cannot_reach_back_in():
    failure = RateError("no card", station="KWLO")
    body = failure.as_dict()
    body["context"]["station"] = "KCRR"
    assert failure.context["station"] == "KWLO"


def test_catching_the_root_catches_a_leaf_raised_anywhere():
    with pytest.raises(CartwallError) as caught:
        TimeError("that runs past midnight")
        raise TimeError("that runs past midnight")
    assert caught.value.code == "bad_time"
