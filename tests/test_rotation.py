import pytest

from cartwall.domain.rotation import (
    Rotation,
    RotationError,
    RotationKind,
    Slice,
)


def test_a_slice_needs_a_cart_and_a_weight_of_at_least_one():
    assert Slice("C1204").weight == 1
    assert str(Slice("C1204")) == "C1204"
    assert str(Slice("C1204", 3)) == "C1204 x3"
    with pytest.raises(RotationError, match="needs a cart"):
        Slice("")
    with pytest.raises(RotationError, match="weighted 0"):
        Slice("C1204", 0)


def test_a_rotation_that_cannot_be_run():
    with pytest.raises(RotationError, match="at least one cart"):
        Rotation.even()
    with pytest.raises(RotationError, match="C1204 is in the rotation twice"):
        Rotation.even("C1204", "C1205", "C1204")
    with pytest.raises(RotationError, match="cannot carry weights"):
        Rotation((Slice("C1204", 2), Slice("C1205")), RotationKind.EVEN)


def test_an_even_rotation_of_three_cycles_strictly():
    rotation = Rotation.even("C1204", "C1205", "C1206")
    assert rotation.assign(6) == [
        "C1204",
        "C1205",
        "C1206",
        "C1204",
        "C1205",
        "C1206",
    ]
    assert rotation.longest_run(60) == 1


def test_an_even_rotation_that_does_not_divide_gives_the_extra_to_the_first():
    rotation = Rotation.even("C1204", "C1205", "C1206")
    assert rotation.shares(10) == {"C1204": 4, "C1205": 3, "C1206": 3}
    assert rotation.shares(11) == {"C1204": 4, "C1205": 4, "C1206": 3}
    assert sum(rotation.shares(47).values()) == 47


def test_a_two_to_one_weighting_gets_its_share_and_still_interleaves():
    rotation = Rotation.weighted([("C1204", 2), ("C1205", 1)])
    assert rotation.assign(9) == [
        "C1204",
        "C1205",
        "C1204",
        "C1204",
        "C1205",
        "C1204",
        "C1204",
        "C1205",
        "C1204",
    ]
    assert rotation.shares(9) == {"C1204": 6, "C1205": 3}
    assert rotation.longest_run(9) == 2


def test_a_heavier_weighting_still_never_bunches_worse_than_it_has_to():
    rotation = Rotation.weighted([("C1204", 3), ("C1205", 1)])
    assert rotation.shares(8) == {"C1204": 6, "C1205": 2}
    assert rotation.longest_run(40) == 3


def test_no_rotation_ever_bunches_two_equal_carts():
    for carts in (2, 3, 4, 5):
        rotation = Rotation.even(*[f"C{1200 + n}" for n in range(carts)])
        assert rotation.longest_run(100) == 1


def test_every_cart_gets_within_one_of_its_proportional_share():
    rotation = Rotation.weighted([("A", 5), ("B", 3), ("C", 2)])
    for count in range(1, 120):
        shares = rotation.shares(count)
        assert sum(shares.values()) == count
        for cart, weight in (("A", 5), ("B", 3), ("C", 2)):
            fair = count * weight / rotation.total_weight
            assert abs(shares[cart] - fair) < 1


def test_a_sequential_rotation_runs_in_order_and_does_not_interleave():
    rotation = Rotation.sequential("C1204", "C1205", "C1206")
    assert not rotation.kind.interleaves
    assert rotation.assign(7) == [
        "C1204",
        "C1205",
        "C1206",
        "C1204",
        "C1205",
        "C1206",
        "C1204",
    ]


def test_assigning_nothing_is_allowed_and_assigning_less_than_nothing_is_not():
    rotation = Rotation.even("C1204", "C1205")
    assert rotation.assign(0) == []
    assert rotation.shares(0) == {"C1204": 0, "C1205": 0}
    assert rotation.longest_run(0) == 0
    with pytest.raises(RotationError, match="negative number"):
        rotation.assign(-1)


def test_a_single_cart_rotation_just_runs_that_cart():
    rotation = Rotation.even("C1204")
    assert rotation.assign(4) == ["C1204"] * 4
    assert rotation.longest_run(4) == 4


def test_the_same_rotation_assigns_the_same_way_every_time():
    rotation = Rotation.weighted([("A", 7), ("B", 5), ("C", 3)])
    first = rotation.assign(200)
    assert first == rotation.assign(200)
    assert first[:100] == rotation.assign(100)


def test_dropping_a_killed_cart_leaves_the_rest_rotating():
    rotation = Rotation.weighted([("C1204", 2), ("C1205", 1), ("C1206", 1)])
    trimmed = rotation.without("C1205")
    assert trimmed.carts == ("C1204", "C1206")
    assert trimmed.total_weight == 3
    assert "C1205" not in trimmed
    assert "C1205" in rotation


def test_dropping_the_last_cart_is_refused_because_the_line_still_has_to_run():
    with pytest.raises(RotationError, match="no copy"):
        Rotation.even("C1204").without("C1204")


def test_a_rotation_reads_the_way_traffic_writes_it_down():
    assert str(Rotation.even("C1204", "C1205")) == "even: C1204, C1205"
    assert str(Rotation.weighted([("C1204", 2), ("C1205", 1)])) == ("weighted: C1204 x2, C1205")
    assert len(Rotation.even("C1204", "C1205")) == 2
