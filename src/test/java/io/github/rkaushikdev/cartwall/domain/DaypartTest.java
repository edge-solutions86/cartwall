package io.github.rkaushikdev.cartwall.domain;

import static io.github.rkaushikdev.cartwall.domain.Daypart.CELLS_IN_WEEK;
import static io.github.rkaushikdev.cartwall.domain.DaypartGrid.standardGrid;
import static io.github.rkaushikdev.cartwall.domain.Weekday.parseDays;
import static io.github.rkaushikdev.cartwall.domain.Weekday.setOf;
import static io.github.rkaushikdev.cartwall.domain.Weekday.showDays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.cartwall.AirTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DaypartTest {

    private static final Weekday MON = Weekday.MON;
    private static final Weekday SAT = Weekday.SAT;
    private static final Weekday SUN = Weekday.SUN;

    @Test
    void theShapesTheSalesSideWritesDaysIn() {
        assertThat(parseDays("M-F")).isEqualTo(setOf(MON, Weekday.TUE, Weekday.WED, Weekday.THU, Weekday.FRI));
        assertThat(parseDays("Sa")).isEqualTo(setOf(SAT));
        assertThat(parseDays("Sa,Su")).isEqualTo(setOf(SAT, SUN));
        assertThat(parseDays("M,W,F")).isEqualTo(setOf(MON, Weekday.WED, Weekday.FRI));
        assertThat(parseDays("M-Su")).hasSize(7);
        assertThat(parseDays("m-f")).isEqualTo(parseDays("M-F"));
        assertThat(parseDays("M - F")).isEqualTo(parseDays("M-F"));
    }

    @Test
    void daysThatAreNotDays() {
        assertThatThrownBy(() -> parseDays("  "))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("no days given");
        assertThatThrownBy(() -> parseDays("Mo"))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("not a day of the week");
        assertThatThrownBy(() -> parseDays("Su-Sa"))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("runs backwards");
    }

    @Test
    void daysPrintBackTheWayTheyWereWritten() {
        for (String text : List.of("M-F", "Sa", "M-Su", "M,W,F", "Sa,Su")) {
            assertThat(showDays(parseDays(text))).isEqualTo(text);
        }
        assertThat(showDays(Set.of())).isEmpty();
        assertThat(showDays(parseDays("M-W,F"))).isEqualTo("M-W,F");
    }

    @Test
    void aDaypartIsASetOfWholeHoursOnWholeDays() {
        Daypart drive = Daypart.build("AMD", "morning drive", "M-F", 6, 10);
        assertThat(drive.hoursADay()).isEqualTo(4);
        assertThat(drive.cells()).hasSize(20);
        assertThat(drive.holds(MON, AirTime.at(6))).isTrue();
        assertThat(drive.holds(MON, AirTime.at(9, 59, 59))).isTrue();
        assertThat(drive.holds(MON, AirTime.at(10))).isFalse();
        assertThat(drive.holds(SAT, AirTime.at(7))).isFalse();
    }

    @Test
    void aDaypartThatCannotExist() {
        assertThatThrownBy(() -> Daypart.build("", "nameless", "M-F", 6, 10))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("needs a code");
        assertThatThrownBy(() -> new Daypart("AMD", "morning drive", Set.of(), 6, 10))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("runs on no days");
        assertThatThrownBy(() -> Daypart.build("AMD", "morning drive", "M-F", 24, 25))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("starts at hour");
        assertThatThrownBy(() -> Daypart.build("AMD", "morning drive", "M-F", 10, 10))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("not after it");
        assertThatThrownBy(() -> Daypart.build("EVE", "evening", "M-F", 19, 25))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("not after it");
    }

    @Test
    void eveningRunsToMidnightAsHourTwentyFour() {
        Daypart evening = Daypart.build("EVE", "evening", "M-F", 19, 24);
        assertThat(evening.holds(MON, AirTime.at(23, 59, 59))).isTrue();
        assertThat(evening.span()).isEqualTo("7p-mid");
    }

    @Test
    void aDaypartReadsTheWayItIsWrittenOnTheCard() {
        assertThat(Daypart.build("AMD", "morning drive", "M-F", 6, 10).span()).isEqualTo("6a-10a");
        assertThat(Daypart.build("MID", "midday", "M-F", 10, 15).span()).isEqualTo("10a-3p");
        assertThat(Daypart.build("OVN", "overnight", "M-Su", 0, 6).span()).isEqualTo("mid-6a");
        assertThat(Daypart.build("LUN", "lunch", "M-F", 11, 12).span()).isEqualTo("11a-noon");
        assertThat(Daypart.build("AMD", "morning drive", "M-F", 6, 10)).hasToString("M-F 6a-10a");
    }

    @Test
    void overlapAndContainmentAreSetQuestions() {
        DaypartGrid grid = standardGrid();
        Daypart drive = grid.of("AMD");
        Daypart tripleA = grid.of("AAA");
        Daypart midday = grid.of("MID");
        assertThat(drive.overlaps(tripleA)).isTrue();
        assertThat(tripleA.containsDaypart(drive)).isTrue();
        assertThat(drive.containsDaypart(tripleA)).isFalse();
        assertThat(drive.overlaps(midday)).isFalse();
        assertThat(grid.of("ROS").containsDaypart(tripleA)).isTrue();
    }

    @Test
    void theGridWillNotTakeTheSameCodeTwice() {
        Daypart drive = Daypart.build("AMD", "morning drive", "M-F", 6, 10);
        assertThatThrownBy(() -> new DaypartGrid(List.of(drive, drive)))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("on the grid twice");
    }

    @Test
    void theGridFindsADaypartByCode() {
        DaypartGrid grid = standardGrid();
        assertThat(grid.of("PMD").name()).isEqualTo("afternoon drive");
        assertThat(grid.has("OVN")).isTrue();
        assertThat(grid.has("XXX")).isFalse();
        assertThatThrownBy(() -> grid.of("XXX"))
                .isInstanceOf(DaypartError.class)
                .hasMessageContaining("not on this grid");
    }

    @Test
    void theStandardGridSellsEveryHourOfTheWeek() {
        DaypartGrid grid = standardGrid();
        assertThat(grid.covered()).hasSize(CELLS_IN_WEEK);
        assertThat(grid.gaps()).isEmpty();
        assertThat(grid.isWholeWeek()).isTrue();
    }

    @Test
    void aGridWithAHoleInItSaysWhichHours() {
        DaypartGrid grid = new DaypartGrid(List.of(
                Daypart.build("AMD", "morning drive", "M-F", 6, 10),
                Daypart.build("PMD", "afternoon drive", "M-F", 15, 19)));
        assertThat(grid.isWholeWeek()).isFalse();
        assertThat(grid.gaps()).contains(new Cell(MON, 12));
        assertThat(grid.gaps()).doesNotContain(new Cell(MON, 7));
        assertThat(grid.gaps()).contains(new Cell(SAT, 7));
    }

    @Test
    void anInstantLandsInEveryDaypartThatHoldsItNarrowestFirst() {
        DaypartGrid grid = standardGrid();
        assertThat(grid.covering(MON, AirTime.at(7, 22)))
                .extracting(Daypart::code)
                .containsExactly("AMD", "AAA", "ROS");
        assertThat(grid.covering(SAT, AirTime.at(7))).extracting(Daypart::code).containsExactly("SAT", "AAA", "ROS");
        assertThat(grid.covering(MON, AirTime.at(2))).extracting(Daypart::code).containsExactly("OVN", "ROS");
        assertThat(grid.covering(MON, AirTime.at(21))).extracting(Daypart::code).containsExactly("EVE", "ROS");
    }

    @Test
    void saturdayAndSundayAreSoldApartFromTheWeekdayGrid() {
        DaypartGrid grid = standardGrid();
        Daypart saturday = grid.of("SAT");
        assertThat(saturday.days()).isEqualTo(setOf(SAT));
        assertThat(saturday.overlaps(grid.of("AMD"))).isFalse();
        assertThat(saturday.overlaps(grid.of("SUN"))).isFalse();
    }

    @Test
    void runOfScheduleIsTheWholeWeekAndHoldsEverythingElse() {
        DaypartGrid grid = standardGrid();
        Daypart ros = grid.of("ROS");
        assertThat(ros.cells()).hasSize(CELLS_IN_WEEK);
        for (Daypart daypart : grid.dayparts()) {
            assertThat(ros.containsDaypart(daypart)).isTrue();
        }
    }
}
