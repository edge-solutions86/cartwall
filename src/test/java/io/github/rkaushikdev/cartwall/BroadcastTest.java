package io.github.rkaushikdev.cartwall;

import static io.github.rkaushikdev.cartwall.BroadcastCalendar.weekStart;
import static io.github.rkaushikdev.cartwall.BroadcastCalendar.weeksBetween;
import static io.github.rkaushikdev.cartwall.BroadcastCalendar.weeksInYear;
import static io.github.rkaushikdev.cartwall.BroadcastCalendar.yearOf;
import static io.github.rkaushikdev.cartwall.BroadcastCalendar.yearStart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 2025 numbers here are the published broadcast calendar, not our output.
 *
 * <p>If a change makes these fail, the change is wrong. Sales quotes flights against this calendar
 * and the buyer's agency is holding the same one.
 */
class BroadcastTest {

    static Stream<Arguments> calendar2025() {
        return Stream.of(
                Arguments.of(1, LocalDate.of(2024, 12, 30), LocalDate.of(2025, 1, 26), 4),
                Arguments.of(2, LocalDate.of(2025, 1, 27), LocalDate.of(2025, 2, 23), 4),
                Arguments.of(3, LocalDate.of(2025, 2, 24), LocalDate.of(2025, 3, 30), 5),
                Arguments.of(4, LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 27), 4),
                Arguments.of(5, LocalDate.of(2025, 4, 28), LocalDate.of(2025, 5, 25), 4),
                Arguments.of(6, LocalDate.of(2025, 5, 26), LocalDate.of(2025, 6, 29), 5),
                Arguments.of(7, LocalDate.of(2025, 6, 30), LocalDate.of(2025, 7, 27), 4),
                Arguments.of(8, LocalDate.of(2025, 7, 28), LocalDate.of(2025, 8, 24), 4),
                Arguments.of(9, LocalDate.of(2025, 8, 25), LocalDate.of(2025, 9, 28), 5),
                Arguments.of(10, LocalDate.of(2025, 9, 29), LocalDate.of(2025, 10, 26), 4),
                Arguments.of(11, LocalDate.of(2025, 10, 27), LocalDate.of(2025, 11, 23), 4),
                Arguments.of(12, LocalDate.of(2025, 11, 24), LocalDate.of(2025, 12, 28), 5));
    }

    @ParameterizedTest(name = "month {0}")
    @MethodSource("calendar2025")
    void the2025BroadcastMonthsMatchThePublishedCalendar(int month, LocalDate opens, LocalDate closes, int weeks) {
        BroadcastMonth bcast = new BroadcastMonth(2025, month);
        assertThat(bcast.start()).isEqualTo(opens);
        assertThat(bcast.end()).isEqualTo(closes);
        assertThat(bcast.weekCount()).isEqualTo(weeks);
    }

    @Test
    void broadcastAugust2025EndsOnTheTwentyFourth() {
        // The one everybody in sales gets wrong.
        assertThat(new BroadcastMonth(2025, 8).end()).isEqualTo(LocalDate.of(2025, 8, 24));
        assertThat(new BroadcastMonth(2025, 9).contains(LocalDate.of(2025, 8, 26)))
                .isTrue();
    }

    @Test
    void theYearOpensOnTheMondayOfTheWeekHoldingJanuaryFirst() {
        assertThat(yearStart(2025)).isEqualTo(LocalDate.of(2024, 12, 30));
        assertThat(yearStart(2024)).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(yearStart(2026)).isEqualTo(LocalDate.of(2025, 12, 29));
    }

    @Test
    void everyMonthOpensOnAMondayAndClosesOnASunday() {
        for (int year = 2020; year < 2036; year++) {
            for (int month = 1; month <= 12; month++) {
                BroadcastMonth bcast = new BroadcastMonth(year, month);
                assertThat(bcast.start().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
                assertThat(bcast.end().getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
            }
        }
    }

    @Test
    void theMonthsTileTheYearWithNoGapAndNoOverlap() {
        for (int year = 2020; year < 2036; year++) {
            LocalDate day = yearStart(year);
            for (int month = 1; month <= 12; month++) {
                BroadcastMonth bcast = new BroadcastMonth(year, month);
                assertThat(bcast.start()).isEqualTo(day);
                day = bcast.end().plusDays(1);
            }
            assertThat(day).isEqualTo(yearStart(year + 1));
        }
    }

    @Test
    void aYearIs52Or53WeeksAndTheLongOnesAreWhereWeExpect() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int year = 2015; year < 2040; year++) {
            counts.put(year, weeksInYear(year));
        }
        assertThat(Set.copyOf(counts.values())).containsExactlyInAnyOrder(52, 53);
        assertThat(counts.entrySet().stream()
                        .filter(entry -> entry.getValue() == 53)
                        .map(Map.Entry::getKey))
                .containsExactly(2017, 2023, 2028, 2034);
    }

    @Test
    void theExtraWeekOfALongYearHangsOnDecember() {
        assertThat(new BroadcastMonth(2023, 12).weekCount()).isEqualTo(5 + 1);
        assertThat(new BroadcastQuarter(2023, 4).weekCount()).isEqualTo(14);
        for (int quarter : List.of(1, 2, 3)) {
            assertThat(new BroadcastQuarter(2023, quarter).weekCount()).isEqualTo(13);
        }
    }

    @Test
    void quartersAreThirteenWeeksInAnOrdinaryYear() {
        for (int quarter = 1; quarter <= 4; quarter++) {
            assertThat(new BroadcastQuarter(2025, quarter).weekCount()).isEqualTo(13);
        }
    }

    @Test
    void aWeekHasToStartOnAMonday() {
        assertThatThrownBy(() -> new BroadcastWeek(LocalDate.of(2025, 8, 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Monday");
    }

    @Test
    void aWeekKnowsItsSevenDays() {
        BroadcastWeek week = BroadcastWeek.of(LocalDate.of(2025, 8, 8));
        assertThat(week.start()).isEqualTo(LocalDate.of(2025, 8, 4));
        assertThat(week.end()).isEqualTo(LocalDate.of(2025, 8, 10));
        assertThat(week.days())
                .isEqualTo(IntStream.range(4, 11)
                        .mapToObj(day -> LocalDate.of(2025, 8, day))
                        .toList());
        assertThat(week.contains(LocalDate.of(2025, 8, 10))).isTrue();
        assertThat(week.contains(LocalDate.of(2025, 8, 11))).isFalse();
        assertThat(week).hasToString("week of 2025-08-04");
    }

    @Test
    void shiftingAWeekMovesWholeWeeksInBothDirections() {
        BroadcastWeek week = new BroadcastWeek(LocalDate.of(2025, 8, 4));
        assertThat(week.shift(3).start()).isEqualTo(LocalDate.of(2025, 8, 25));
        assertThat(week.shift(-1).start()).isEqualTo(LocalDate.of(2025, 7, 28));
        assertThat(week.shift(0)).isEqualTo(week);
    }

    @Test
    void weekStartPinsAnyDayToItsMonday() {
        assertThat(weekStart(LocalDate.of(2025, 8, 4))).isEqualTo(LocalDate.of(2025, 8, 4));
        assertThat(weekStart(LocalDate.of(2025, 8, 10))).isEqualTo(LocalDate.of(2025, 8, 4));
    }

    @Test
    void theYearADayBelongsToIsNotAlwaysItsCalendarYear() {
        assertThat(yearOf(LocalDate.of(2024, 12, 30))).isEqualTo(2025);
        assertThat(yearOf(LocalDate.of(2025, 1, 1))).isEqualTo(2025);
        assertThat(yearOf(LocalDate.of(2025, 12, 28))).isEqualTo(2025);
        assertThat(yearOf(LocalDate.of(2025, 12, 29))).isEqualTo(2026);
    }

    @Test
    void findingTheMonthADayFallsIn() {
        assertThat(BroadcastMonth.of(LocalDate.of(2025, 8, 24))).isEqualTo(new BroadcastMonth(2025, 8));
        assertThat(BroadcastMonth.of(LocalDate.of(2025, 8, 25))).isEqualTo(new BroadcastMonth(2025, 9));
        assertThat(BroadcastMonth.of(LocalDate.of(2024, 12, 30))).isEqualTo(new BroadcastMonth(2025, 1));
        assertThat(BroadcastMonth.of(LocalDate.of(2025, 12, 29))).isEqualTo(new BroadcastMonth(2026, 1));
    }

    @Test
    void everyDayOfALongStretchLandsInTheMonthThatClaimsIt() {
        LocalDate day = LocalDate.of(2022, 1, 3);
        while (day.isBefore(LocalDate.of(2030, 1, 1))) {
            assertThat(BroadcastMonth.of(day).contains(day)).isTrue();
            day = day.plusDays(1);
        }
    }

    @Test
    void walkingMonthsForwardAndBackOverTheYearBoundary() {
        BroadcastMonth december = new BroadcastMonth(2025, 12);
        assertThat(december.next()).isEqualTo(new BroadcastMonth(2026, 1));
        assertThat(december.next().previous()).isEqualTo(december);
        assertThat(new BroadcastMonth(2025, 1).previous()).isEqualTo(new BroadcastMonth(2024, 12));
    }

    @Test
    void aMonthListsItsWeeksInOrder() {
        List<BroadcastWeek> weeks = new BroadcastMonth(2025, 9).weeks();
        assertThat(weeks).hasSize(5);
        assertThat(weeks.get(0).start()).isEqualTo(LocalDate.of(2025, 8, 25));
        assertThat(weeks.get(weeks.size() - 1).end()).isEqualTo(LocalDate.of(2025, 9, 28));
        for (int index = 1; index < weeks.size(); index++) {
            assertThat(weeks.get(index).start()).isAfter(weeks.get(index - 1).start());
        }
    }

    @Test
    void monthsParseTheWaySalesWritesThem() {
        assertThat(BroadcastMonth.parse("2025-08")).isEqualTo(new BroadcastMonth(2025, 8));
        assertThat(BroadcastMonth.parse("2025-08")).hasToString("2025-08");
        assertThatThrownBy(() -> BroadcastMonth.parse("August"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a broadcast month");
        assertThatThrownBy(() -> new BroadcastMonth(2025, 13))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a month");
    }

    @Test
    void quartersKnowTheirMonthsAndTheirSpan() {
        BroadcastQuarter third = new BroadcastQuarter(2025, 3);
        assertThat(third.months()).extracting(BroadcastMonth::month).containsExactly(7, 8, 9);
        assertThat(third.start()).isEqualTo(LocalDate.of(2025, 6, 30));
        assertThat(third.end()).isEqualTo(LocalDate.of(2025, 9, 28));
        assertThat(third).hasToString("2025Q3");
        assertThat(BroadcastQuarter.of(LocalDate.of(2025, 8, 25))).isEqualTo(third);
        assertThatThrownBy(() -> new BroadcastQuarter(2025, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a quarter");
    }

    @Test
    void theWeeksAFlightTouchesIncludeBothPartialEnds() {
        List<BroadcastWeek> weeks = weeksBetween(LocalDate.of(2025, 8, 6), LocalDate.of(2025, 8, 19));
        assertThat(weeks)
                .extracting(BroadcastWeek::start)
                .containsExactly(LocalDate.of(2025, 8, 4), LocalDate.of(2025, 8, 11), LocalDate.of(2025, 8, 18));
        assertThat(weeksBetween(LocalDate.of(2025, 8, 6), LocalDate.of(2025, 8, 6))
                        .get(0)
                        .start())
                .isEqualTo(LocalDate.of(2025, 8, 4));
        assertThatThrownBy(() -> weeksBetween(LocalDate.of(2025, 8, 19), LocalDate.of(2025, 8, 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("backwards");
    }

    @Test
    void weeksAndMonthsSortByWhenTheyAir() {
        assertThat(new BroadcastWeek(LocalDate.of(2025, 8, 4)))
                .isLessThan(new BroadcastWeek(LocalDate.of(2025, 8, 11)));
        assertThat(new BroadcastMonth(2025, 3)).isLessThan(new BroadcastMonth(2025, 11));
        assertThat(new BroadcastMonth(2024, 12)).isLessThan(new BroadcastMonth(2025, 1));
        assertThat(Stream.of(new BroadcastQuarter(2025, 4), new BroadcastQuarter(2025, 1))
                        .sorted()
                        .toList()
                        .get(0)
                        .quarter())
                .isEqualTo(1);
    }
}
