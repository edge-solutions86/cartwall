package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.Money.ZERO;
import static io.github.rkaushikdev.cartwall.Money.commission;
import static io.github.rkaushikdev.cartwall.Money.roundTrips;
import static io.github.rkaushikdev.cartwall.Money.toGross;
import static io.github.rkaushikdev.cartwall.Money.toNet;
import static io.github.rkaushikdev.cartwall.Money.total;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void amountsParseTheWayTheyAreWrittenOnAnOrder() {
        assertThat(Money.parse("$1,250.00")).isEqualTo(new Money(125000));
        assertThat(Money.parse("1250")).isEqualTo(new Money(125000));
        assertThat(Money.parse("45.5")).isEqualTo(new Money(4550));
        assertThat(Money.parse("-45.50")).isEqualTo(new Money(-4550));
        assertThat(Money.parse("  $0.07 ")).isEqualTo(new Money(7));
    }

    @Test
    void amountsThatAreNotAmounts() {
        for (String text : List.of("", "free", "1.234", "$1.2.3", "1,25o")) {
            assertThatThrownBy(() -> Money.parse(text))
                    .isInstanceOf(MoneyError.class)
                    .hasMessageContaining("not an amount");
        }
    }

    @Test
    void dollarsComeInAsDecimalAndRoundHalfUpToTheCent() {
        assertThat(Money.dollars(new BigDecimal("12.345"))).isEqualTo(new Money(1235));
        assertThat(Money.dollars(new BigDecimal("12.344"))).isEqualTo(new Money(1234));
        assertThat(Money.dollars(75)).isEqualTo(new Money(7500));
        assertThat(Money.dollars("18.50")).isEqualTo(new Money(1850));
    }

    @Test
    void noFloatEverBecomesMoney() {
        // The constructor takes whole cents and nothing else, so a fractional amount has to go
        // through dollars(), which names the rounding, and the compiler refuses the rest.
        Constructor<?>[] constructors = Money.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(long.class);
    }

    @Test
    void amountsPrintWithAThousandsComma() {
        assertThat(new Money(125000)).hasToString("$1,250.00");
        assertThat(new Money(7)).hasToString("$0.07");
        assertThat(new Money(-4550)).hasToString("-$45.50");
        assertThat(new Money(0)).hasToString("$0.00");
        assertThat(new Money(123456789)).hasToString("$1,234,567.89");
    }

    @Test
    void amountsAddSubtractNegateAndMultiplyByACount() {
        assertThat(new Money(1000).plus(new Money(250))).isEqualTo(new Money(1250));
        assertThat(new Money(1000).minus(new Money(1250))).isEqualTo(new Money(-250));
        assertThat(new Money(250).negate()).isEqualTo(new Money(-250));
        assertThat(new Money(-250).abs()).isEqualTo(new Money(250));
        assertThat(new Money(1250).times(4)).isEqualTo(new Money(5000));
        assertThat(ZERO.isZero()).isTrue();
        assertThat(new Money(1).isZero()).isFalse();
    }

    @Test
    void moneyDoesNotMultiplyByARateByAccident() {
        // Multiplying takes a count. A rate goes through scale, which names the rounding.
        List<Method> multiplies = Arrays.stream(Money.class.getMethods())
                .filter(method -> method.getName().equals("times"))
                .toList();
        assertThat(multiplies).isNotEmpty();
        for (Method method : multiplies) {
            assertThat(method.getParameterTypes()).containsExactly(long.class);
        }
    }

    @Test
    void aPercentageOfAnAmountRoundsHalfUp() {
        assertThat(Money.parse("100.00").scale(new BigDecimal("0.15"))).isEqualTo(Money.parse("15.00"));
        assertThat(Money.parse("117.65").scale(new BigDecimal("0.15"))).isEqualTo(Money.parse("17.65"));
        assertThat(Money.parse("0.03").scale(new BigDecimal("0.5"))).isEqualTo(Money.parse("0.02"));
    }

    @Test
    void totallingAColumn() {
        assertThat(total(List.of(new Money(100), new Money(250), new Money(-50))))
                .isEqualTo(new Money(300));
        assertThat(total(List.of())).isEqualTo(ZERO);
    }

    @Test
    void anEvenSplitThatDoesNotDivideStillAddsBackUp() {
        List<Money> pieces = Money.parse("1000.00").split(3);
        assertThat(pieces).containsExactly(new Money(33334), new Money(33333), new Money(33333));
        assertThat(total(pieces)).isEqualTo(Money.parse("1000.00"));
    }

    @Test
    void aWeightedSplitGivesTheLeftoverCentsToTheWorstRobbed() {
        List<Money> pieces = Money.parse("100.00").allocate(List.of(1, 1, 1));
        assertThat(pieces).extracting(Money::toString).containsExactly("$33.34", "$33.33", "$33.33");
        List<Money> weighted = Money.parse("100.00").allocate(List.of(5, 3, 2));
        assertThat(weighted).containsExactly(new Money(5000), new Money(3000), new Money(2000));
        assertThat(total(weighted)).isEqualTo(Money.parse("100.00"));
    }

    @Test
    void aZeroWeightGetsNothingAndCostsNobodyACent() {
        assertThat(Money.parse("10.00").allocate(List.of(1, 0, 1)))
                .containsExactly(new Money(500), new Money(0), new Money(500));
    }

    @Test
    void aNegativeAmountAllocatesTheSameWay() {
        List<Money> pieces = Money.parse("-10.00").allocate(List.of(1, 1, 1));
        assertThat(total(pieces)).isEqualTo(Money.parse("-10.00"));
        assertThat(pieces).allSatisfy(piece -> assertThat(piece.cents()).isNegative());
    }

    @Test
    void allocationsThatCannotBeMade() {
        assertThatThrownBy(() -> new Money(100).allocate(List.of()))
                .isInstanceOf(MoneyError.class)
                .hasMessageContaining("nothing to allocate");
        assertThatThrownBy(() -> new Money(100).allocate(List.of(1, -1)))
                .isInstanceOf(MoneyError.class)
                .hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> new Money(100).allocate(List.of(0, 0)))
                .isInstanceOf(MoneyError.class)
                .hasMessageContaining("all zero");
        assertThatThrownBy(() -> new Money(100).split(0))
                .isInstanceOf(MoneyError.class)
                .hasMessageContaining("fewer than one way");
    }

    @Test
    void aFourWeekFlightSplitOverFiveWeeksStillAddsBackUp() {
        for (long cents = 9990; cents < 10010; cents++) {
            for (int ways : List.of(2, 3, 4, 5, 6, 7, 13)) {
                assertThat(total(new Money(cents).split(ways))).isEqualTo(new Money(cents));
            }
        }
    }

    @Test
    void theCommissionComesOutOfTheGrossNotOffTheNet() {
        Money gross = Money.parse("1,000.00");
        assertThat(commission(gross)).isEqualTo(Money.parse("150.00"));
        assertThat(toNet(gross)).isEqualTo(Money.parse("850.00"));
    }

    @Test
    void grossingANetBackUpDividesRatherThanAddingFifteenPercent() {
        Money net = Money.parse("100.00");
        assertThat(toGross(net)).isEqualTo(Money.parse("117.65"));
        assertThat(net.plus(commission(net))).isEqualTo(Money.parse("115.00"));
        assertThat(toGross(net)).isNotEqualTo(net.plus(commission(net)));
    }

    @Test
    void aGrossOnTheRateCardComesHomeFromNet() {
        for (String text : List.of("100.00", "1,000.00", "18.00", "425.00")) {
            assertThat(roundTrips(Money.parse(text))).as(text).isTrue();
        }
    }

    @Test
    void aGrossOffTheCardDoesNotAlwaysComeHome() {
        assertThat(toNet(new Money(23))).isEqualTo(new Money(20));
        assertThat(toGross(new Money(20))).isEqualTo(new Money(24));
        assertThat(roundTrips(new Money(23))).isFalse();
        long broken = LongStream.range(1, 700)
                .filter(cents -> !roundTrips(new Money(cents)))
                .count();
        assertThat(broken)
                .as("about one in seven, and the warning depends on it")
                .isEqualTo(105);
    }

    @Test
    void grossingUpIsTheDirectionThatNeverLosesACent() {
        // Net to gross to net always comes home, because the gross is spread out.
        for (long cents = 1; cents < 2000; cents++) {
            assertThat(toNet(toGross(new Money(cents)))).isEqualTo(new Money(cents));
        }
    }

    @Test
    void aHouseRateOfZeroCommissionIsExactBothWays() {
        BigDecimal direct = BigDecimal.ZERO;
        for (long cents = 1; cents < 200; cents++) {
            assertThat(toGross(new Money(cents), direct)).isEqualTo(new Money(cents));
            assertThat(roundTrips(new Money(cents), direct)).isTrue();
        }
    }

    @Test
    void aCommissionOfEverythingCannotBeGrossedUp() {
        assertThatThrownBy(() -> toGross(new Money(1000), BigDecimal.ONE))
                .isInstanceOf(MoneyError.class)
                .hasMessageContaining("commission of everything");
    }
}
