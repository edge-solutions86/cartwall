package io.github.rkaushikdev.cartwall.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RotationTest {

    private static int sum(Map<String, Integer> shares) {
        return shares.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void aSliceNeedsACartAndAWeightOfAtLeastOne() {
        assertThat(new Slice("C1204").weight()).isEqualTo(1);
        assertThat(new Slice("C1204")).hasToString("C1204");
        assertThat(new Slice("C1204", 3)).hasToString("C1204 x3");
        assertThatThrownBy(() -> new Slice(""))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("needs a cart");
        assertThatThrownBy(() -> new Slice("C1204", 0))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("weighted 0");
    }

    @Test
    void aRotationThatCannotBeRun() {
        assertThatThrownBy(Rotation::even).isInstanceOf(RotationError.class).hasMessageContaining("at least one cart");
        assertThatThrownBy(() -> Rotation.even("C1204", "C1205", "C1204"))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("C1204 is in the rotation twice");
        assertThatThrownBy(() -> new Rotation(List.of(new Slice("C1204", 2), new Slice("C1205")), RotationKind.EVEN))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("cannot carry weights");
    }

    @Test
    void anEvenRotationOfThreeCyclesStrictly() {
        Rotation rotation = Rotation.even("C1204", "C1205", "C1206");
        assertThat(rotation.assign(6)).containsExactly("C1204", "C1205", "C1206", "C1204", "C1205", "C1206");
        assertThat(rotation.longestRun(60)).isEqualTo(1);
    }

    @Test
    void anEvenRotationThatDoesNotDivideGivesTheExtraToTheFirst() {
        Rotation rotation = Rotation.even("C1204", "C1205", "C1206");
        assertThat(rotation.shares(10)).isEqualTo(Map.of("C1204", 4, "C1205", 3, "C1206", 3));
        assertThat(rotation.shares(11)).isEqualTo(Map.of("C1204", 4, "C1205", 4, "C1206", 3));
        assertThat(sum(rotation.shares(47))).isEqualTo(47);
    }

    @Test
    void aTwoToOneWeightingGetsItsShareAndStillInterleaves() {
        Rotation rotation = Rotation.weighted(new Slice("C1204", 2), new Slice("C1205", 1));
        assertThat(rotation.assign(9))
                .containsExactly("C1204", "C1205", "C1204", "C1204", "C1205", "C1204", "C1204", "C1205", "C1204");
        assertThat(rotation.shares(9)).isEqualTo(Map.of("C1204", 6, "C1205", 3));
        assertThat(rotation.longestRun(9)).isEqualTo(2);
    }

    @Test
    void aHeavierWeightingStillNeverBunchesWorseThanItHasTo() {
        Rotation rotation = Rotation.weighted(new Slice("C1204", 3), new Slice("C1205", 1));
        assertThat(rotation.shares(8)).isEqualTo(Map.of("C1204", 6, "C1205", 2));
        assertThat(rotation.longestRun(40)).isEqualTo(3);
    }

    @Test
    void noRotationEverBunchesTwoEqualCarts() {
        for (int carts = 2; carts <= 5; carts++) {
            String[] names =
                    IntStream.range(0, carts).mapToObj(n -> "C" + (1200 + n)).toArray(String[]::new);
            assertThat(Rotation.even(names).longestRun(100)).isEqualTo(1);
        }
    }

    @Test
    void everyCartGetsWithinOneOfItsProportionalShare() {
        Rotation rotation = Rotation.weighted(new Slice("A", 5), new Slice("B", 3), new Slice("C", 2));
        Map<String, Integer> weights = Map.of("A", 5, "B", 3, "C", 2);
        for (int count = 1; count < 120; count++) {
            Map<String, Integer> shares = rotation.shares(count);
            assertThat(sum(shares)).isEqualTo(count);
            for (Map.Entry<String, Integer> weight : weights.entrySet()) {
                double fair = count * weight.getValue() / (double) rotation.totalWeight();
                assertThat(Math.abs(shares.get(weight.getKey()) - fair)).isLessThan(1);
            }
        }
    }

    @Test
    void aSequentialRotationRunsInOrderAndDoesNotInterleave() {
        Rotation rotation = Rotation.sequential("C1204", "C1205", "C1206");
        assertThat(rotation.kind().interleaves()).isFalse();
        assertThat(rotation.assign(7)).containsExactly("C1204", "C1205", "C1206", "C1204", "C1205", "C1206", "C1204");
    }

    @Test
    void assigningNothingIsAllowedAndAssigningLessThanNothingIsNot() {
        Rotation rotation = Rotation.even("C1204", "C1205");
        assertThat(rotation.assign(0)).isEmpty();
        assertThat(rotation.shares(0)).isEqualTo(Map.of("C1204", 0, "C1205", 0));
        assertThat(rotation.longestRun(0)).isZero();
        assertThatThrownBy(() -> rotation.assign(-1))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("negative number");
    }

    @Test
    void aSingleCartRotationJustRunsThatCart() {
        Rotation rotation = Rotation.even("C1204");
        assertThat(rotation.assign(4)).isEqualTo(Collections.nCopies(4, "C1204"));
        assertThat(rotation.longestRun(4)).isEqualTo(4);
    }

    @Test
    void theSameRotationAssignsTheSameWayEveryTime() {
        Rotation rotation = Rotation.weighted(new Slice("A", 7), new Slice("B", 5), new Slice("C", 3));
        List<String> first = rotation.assign(200);
        assertThat(rotation.assign(200)).isEqualTo(first);
        assertThat(rotation.assign(100)).isEqualTo(first.subList(0, 100));
    }

    @Test
    void droppingAKilledCartLeavesTheRestRotating() {
        Rotation rotation = Rotation.weighted(new Slice("C1204", 2), new Slice("C1205", 1), new Slice("C1206", 1));
        Rotation trimmed = rotation.without("C1205");
        assertThat(trimmed.carts()).containsExactly("C1204", "C1206");
        assertThat(trimmed.totalWeight()).isEqualTo(3);
        assertThat(trimmed.contains("C1205")).isFalse();
        assertThat(rotation.contains("C1205")).isTrue();
    }

    @Test
    void droppingTheLastCartIsRefusedBecauseTheLineStillHasToRun() {
        assertThatThrownBy(() -> Rotation.even("C1204").without("C1204"))
                .isInstanceOf(RotationError.class)
                .hasMessageContaining("no copy");
    }

    @Test
    void aRotationReadsTheWayTrafficWritesItDown() {
        assertThat(Rotation.even("C1204", "C1205")).hasToString("even: C1204, C1205");
        assertThat(Rotation.weighted(new Slice("C1204", 2), new Slice("C1205", 1)))
                .hasToString("weighted: C1204 x2, C1205");
        assertThat(Rotation.even("C1204", "C1205").size()).isEqualTo(2);
    }
}
