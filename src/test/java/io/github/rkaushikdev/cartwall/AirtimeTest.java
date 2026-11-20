package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.AirTime.MIDNIGHT;
import static io.github.rkaushikdev.cartwall.AirTime.backtime;
import static io.github.rkaushikdev.cartwall.Duration.total;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AirtimeTest {

    @Test
    void aLengthCanBeWrittenTheFiveWaysTheHouseWritesIt() {
        assertThat(Duration.parse(":30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.parse("30")).isEqualTo(Duration.ofSeconds(30));
        assertThat(Duration.parse("1:00")).isEqualTo(Duration.ofSeconds(60));
        assertThat(Duration.parse("00:01:30")).isEqualTo(Duration.ofSeconds(90));
        assertThat(Duration.parse("1:00.5")).isEqualTo(new Duration(605));
    }

    @Test
    void aLengthThatIsNotALength() {
        for (String text : List.of("", "thirty", "1:2:3:4", "-30", "30.55")) {
            assertThatThrownBy(() -> Duration.parse(text))
                    .isInstanceOf(TimeError.class)
                    .hasMessageContaining("not a length");
        }
    }

    @Test
    void lengthsPrintTheWayTheLogPrintsThem() {
        assertThat(Duration.ofSeconds(30)).hasToString(":30");
        assertThat(Duration.ofSeconds(60)).hasToString("1:00");
        assertThat(Duration.ofSeconds(90)).hasToString("1:30");
        assertThat(new Duration(605)).hasToString("1:00.5");
        assertThat(Duration.ofSeconds(3725)).hasToString("1:02:05");
        assertThat(new Duration(0)).hasToString(":00");
    }

    @Test
    void lengthsAddSubtractAndMultiply() {
        Duration thirty = Duration.ofSeconds(30);
        assertThat(thirty.plus(thirty)).isEqualTo(Duration.ofSeconds(60));
        assertThat(thirty.times(4)).isEqualTo(Duration.ofSeconds(120));
        assertThat(Duration.ofSeconds(60).minus(thirty)).isEqualTo(thirty);
        assertThat(new Duration(0).isZero()).isTrue();
        assertThat(thirty.isZero()).isFalse();
    }

    @Test
    void aLengthCannotGoNegativeHoweverItIsReached() {
        assertThatThrownBy(() -> new Duration(-1)).isInstanceOf(TimeError.class).hasMessageContaining("backwards");
        assertThatThrownBy(() -> Duration.ofSeconds(30).minus(Duration.ofSeconds(31)))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void tenthsHaveToBeWhole() {
        // The constructor takes an int and nothing else, so a fractional tenth such as 29.7
        // cannot be written down at all.
        Constructor<?>[] constructors = Duration.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(int.class);
    }

    @Test
    void aPartSecondCutStillEatsAWholeSecondOfTheBreak() {
        assertThat(new Duration(301).wholeSeconds()).isEqualTo(31);
        assertThat(new Duration(300).wholeSeconds()).isEqualTo(30);
        assertThat(new Duration(299).wholeSeconds()).isEqualTo(30);
        assertThat(new Duration(301).seconds()).isEqualTo(30.1);
    }

    @Test
    void aBreakFullOfOddCutsDoesNotDrift() {
        // Sixty cuts that each run 30.1 come to exactly 30:06, not 30:05.999...
        List<Duration> cuts = Collections.nCopies(60, new Duration(301));
        assertThat(total(cuts)).isEqualTo(new Duration(18060));
        assertThat(total(cuts)).hasToString("30:06");
    }

    @Test
    void airTimesParseInBothClocks() {
        assertThat(AirTime.parse("6:00a")).isEqualTo(AirTime.at(6));
        assertThat(AirTime.parse("6:00:00")).isEqualTo(AirTime.at(6));
        assertThat(AirTime.parse("12:00a")).isEqualTo(MIDNIGHT);
        assertThat(AirTime.parse("12:00p")).isEqualTo(AirTime.at(12));
        assertThat(AirTime.parse("2:15:30p")).isEqualTo(AirTime.at(14, 15, 30));
        assertThat(AirTime.parse("14:15:30")).isEqualTo(AirTime.at(14, 15, 30));
        assertThat(AirTime.parse("11:59:59p")).isEqualTo(AirTime.at(23, 59, 59));
    }

    @Test
    void airTimesThatAreNotAirTimes() {
        for (String text : List.of("", "6a", "25:00:00", "6:60:00", "noon")) {
            assertThatThrownBy(() -> AirTime.parse(text)).isInstanceOf(TimeError.class);
        }
        assertThatThrownBy(() -> AirTime.parse("14:00p"))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("twelve hour");
    }

    @Test
    void airTimesPrintBothWays() {
        assertThat(AirTime.at(6)).hasToString("06:00:00");
        assertThat(AirTime.at(14, 15, 30)).hasToString("14:15:30");
        assertThat(AirTime.at(14, 15, 30).twelveHour()).isEqualTo("2:15:30p");
        assertThat(AirTime.at(0, 5).twelveHour()).isEqualTo("12:05:00a");
        assertThat(AirTime.at(12).twelveHour()).isEqualTo("12:00:00p");
        assertThat(new AirTime(216001)).hasToString("06:00:00.1");
    }

    @Test
    void anAirTimeKnowsWhereItSitsInItsHour() {
        AirTime spot = AirTime.at(7, 22, 30);
        assertThat(spot.hour()).isEqualTo(7);
        assertThat(spot.minute()).isEqualTo(22);
        assertThat(spot.second()).isEqualTo(30);
        assertThat(spot.topOfHour()).isEqualTo(AirTime.at(7));
        assertThat(spot.intoHour()).isEqualTo(Duration.ofSeconds(22 * 60 + 30));
        assertThat(AirTime.at(7).intoHour()).isEqualTo(new Duration(0));
    }

    @Test
    void aLengthAddedToAPositionGivesAPosition() {
        assertThat(AirTime.at(7, 22).plus(Duration.ofSeconds(30))).isEqualTo(AirTime.at(7, 22, 30));
        assertThat(AirTime.at(7, 22, 30).minus(Duration.ofSeconds(30))).isEqualTo(AirTime.at(7, 22));
    }

    @Test
    void twoPositionsSubtractToALength() {
        Duration gap = AirTime.at(7, 30).minus(AirTime.at(7, 22));
        assertThat(gap).isEqualTo(Duration.ofSeconds(480));
    }

    @Test
    void nothingMayRunPastMidnightInEitherDirection() {
        assertThatThrownBy(() -> AirTime.at(23, 59, 30).plus(Duration.ofSeconds(31)))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("past midnight");
        assertThatThrownBy(() -> MIDNIGHT.minus(Duration.ofSeconds(1)))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("before midnight");
        assertThatThrownBy(() -> new AirTime(864000))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("outside the broadcast day");
    }

    @Test
    void airTimesSortByWhenTheyAir() {
        List<AirTime> times = List.of(AirTime.at(14), AirTime.at(6), AirTime.at(9, 30));
        assertThat(times.stream().sorted().toList()).containsExactly(AirTime.at(6), AirTime.at(9, 30), AirTime.at(14));
    }

    @Test
    void aBreakBacktimedOffTheNetworkJoinEndsExactlyOnIt() {
        AirTime join = AirTime.at(8, 59, 30);
        List<Duration> lengths =
                List.of(Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(30), Duration.ofSeconds(10));
        List<AirTime> starts = backtime(join, lengths);
        assertThat(starts.get(0)).isEqualTo(AirTime.at(8, 57, 20));
        assertThat(starts)
                .containsExactly(
                        AirTime.at(8, 57, 20), AirTime.at(8, 57, 50), AirTime.at(8, 58, 50), AirTime.at(8, 59, 20));
        assertThat(starts.get(starts.size() - 1).plus(lengths.get(lengths.size() - 1)))
                .isEqualTo(join);
    }

    @Test
    void backtimingNothingStartsNothing() {
        assertThat(backtime(AirTime.at(8), List.of())).isEmpty();
    }

    @Test
    void backtimingMoreThanFitsBeforeMidnightRefuses() {
        assertThatThrownBy(() -> backtime(AirTime.at(0, 0, 20), List.of(Duration.ofSeconds(30))))
                .isInstanceOf(TimeError.class)
                .hasMessageContaining("before midnight");
    }
}
