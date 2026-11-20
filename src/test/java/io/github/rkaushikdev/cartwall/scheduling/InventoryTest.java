package io.github.rkaushikdev.cartwall.scheduling;

import static io.github.rkaushikdev.cartwall.domain.Clock.musicClock;
import static io.github.rkaushikdev.cartwall.domain.Clock.talkClock;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.SIXTY;
import static io.github.rkaushikdev.cartwall.domain.SpotLength.THIRTY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.cartwall.AirTime;
import io.github.rkaushikdev.cartwall.Duration;
import io.github.rkaushikdev.cartwall.domain.Assignment;
import io.github.rkaushikdev.cartwall.domain.ClassOfTime;
import io.github.rkaushikdev.cartwall.domain.ClockGrid;
import io.github.rkaushikdev.cartwall.domain.SpotLength;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryTest {

    private static final LocalDate MONDAY = LocalDate.of(2025, 8, 4);
    private static final LocalDate FRIDAY = LocalDate.of(2025, 8, 8);

    private ClockGrid grid;
    private Avail avail;

    static Placement aPlacement(int line, SpotLength length) {
        return new Placement("25-1044", line, "HYVEE", length, ClassOfTime.FIXED);
    }

    static Placement aPlacement(int line) {
        return aPlacement(line, THIRTY);
    }

    @BeforeEach
    void fileTheGrid() {
        grid = new ClockGrid("KWLO", LocalDate.of(2025, 1, 1));
        grid.fileClock(musicClock());
        grid.fileClock(talkClock());
        grid.assign(Assignment.build("all week", "M-Su", 0, 24, "MUS"));
        grid.assign(Assignment.build("farm block", "M-F", 5, 6, "TLK"));
        grid.assign(Assignment.preempt("friday football", "F", 19, 23));
        avail = Avail.of("KWLO", MONDAY, 7, musicClock().numbered(2));
    }

    @Test
    void aPlacementNeedsAnOrderAndALine() {
        assertThatThrownBy(() -> new Placement("", 1, "HYVEE", THIRTY, ClassOfTime.FIXED))
                .isInstanceOf(InventoryError.class)
                .hasMessageContaining("names no order");
        assertThatThrownBy(() -> aPlacement(0))
                .isInstanceOf(InventoryError.class)
                .hasMessageContaining("not a line number");
        assertThat(aPlacement(1).key()).isEqualTo(new LineKey("25-1044", 1));
        assertThat(aPlacement(1)).hasToString("25-1044-1 HYVEE :30");
    }

    @Test
    void anEmptyAvailKnowsBothOfItsCapacities() {
        assertThat(avail.units()).isEqualTo(6);
        assertThat(avail.capacity()).isEqualTo(Duration.ofSeconds(180));
        assertThat(avail.freeUnits()).isEqualTo(6);
        assertThat(avail.freeLength()).isEqualTo(Duration.ofSeconds(180));
        assertThat(avail.isFull()).isFalse();
        assertThat(avail.startsAt()).isEqualTo(AirTime.at(7, 10));
        assertThat(avail).hasToString("KWLO 2025-08-04 07:10:00 break 2");
    }

    @Test
    void sixThirtiesFillTheBreakOnBothCounts() {
        IntStream.rangeClosed(1, 6).forEach(line -> avail.add(aPlacement(line)));
        assertThat(avail.usedUnits()).isEqualTo(6);
        assertThat(avail.freeUnits()).isZero();
        assertThat(avail.freeLength()).isEqualTo(new Duration(0));
        assertThat(avail.isFull()).isTrue();
    }

    @Test
    void threeSixtiesFillItOnSecondsWithUnitsToSpare() {
        IntStream.rangeClosed(1, 3).forEach(line -> avail.add(aPlacement(line, SIXTY)));
        assertThat(avail.usedUnits()).isEqualTo(6);
        assertThat(avail.freeLength()).isEqualTo(new Duration(0));
        assertThat(avail.isFull()).isTrue();
    }

    @Test
    void twoSixtiesAndAThirtyStillLeaveRoomForOneMore() {
        avail.add(aPlacement(1, SIXTY));
        avail.add(aPlacement(2, SIXTY));
        avail.add(aPlacement(3, THIRTY));
        assertThat(avail.usedUnits()).isEqualTo(5);
        assertThat(avail.freeUnits()).isEqualTo(1);
        assertThat(avail.freeLength()).isEqualTo(Duration.ofSeconds(30));
        assertThat(avail.accepts(THIRTY)).isTrue();
        assertThat(avail.accepts(SIXTY)).isFalse();
    }

    @Test
    void aShortSpotCostsAWholeUnitEvenThoughItLeavesSeconds() {
        IntStream.rangeClosed(1, 6).forEach(line -> avail.add(aPlacement(line, new SpotLength(10))));
        assertThat(avail.freeUnits()).isZero();
        assertThat(avail.freeLength()).isEqualTo(Duration.ofSeconds(120));
        assertThat(avail.isFull()).isTrue();
        assertThat(avail.accepts(new SpotLength(10))).isFalse();
    }

    @Test
    void aFullBreakRefusesAndSaysWhatIsLeft() {
        avail.add(aPlacement(1, SIXTY));
        avail.add(aPlacement(2, SIXTY));
        avail.add(aPlacement(3, SIXTY));
        BreakFull caught = catchThrowableOfType(BreakFull.class, () -> avail.add(aPlacement(4)));
        assertThat(caught).hasMessageContaining("will not take a :30");
        assertThat(caught.code()).isEqualTo("break_full");
        assertThat(caught.context()).containsEntry("free_units", 0).containsEntry("free_seconds", 0.0);
    }

    @Test
    void aSpotCanBePulledBackOutAndTheRoomComesBack() {
        avail.add(aPlacement(1, SIXTY));
        avail.add(aPlacement(2, THIRTY));
        assertThat(avail.holds("25-1044", 1)).isTrue();
        Placement dropped = avail.drop("25-1044", 1);
        assertThat(dropped.length()).isEqualTo(SIXTY);
        assertThat(avail.freeUnits()).isEqualTo(5);
        assertThat(avail.holds("25-1044", 1)).isFalse();
        assertThatThrownBy(() -> avail.drop("25-1044", 1))
                .isInstanceOf(InventoryError.class)
                .hasMessageContaining("is not in KWLO hour 7");
    }

    @Test
    void airTimesFallOutOfTheLengthsInFront() {
        avail.add(aPlacement(1, THIRTY));
        avail.add(aPlacement(2, SIXTY));
        avail.add(aPlacement(3, new SpotLength(15)));
        List<Timed> timed = avail.airTimes();
        assertThat(timed)
                .extracting(Timed::at)
                .containsExactly(AirTime.at(7, 10), AirTime.at(7, 10, 30), AirTime.at(7, 11, 30));
        assertThat(timed).extracting(each -> each.placement().line()).containsExactly(1, 2, 3);
    }

    @Test
    void aBoardLaysOutTheSellingBreaksOfEveryHour() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        // Twenty three music hours at four sellable breaks, plus the farm hour at four.
        assertThat(board.size()).isEqualTo(23 * 4 + 4);
        assertThat(board.hours()).isEqualTo(IntStream.range(0, 24).boxed().toList());
        assertThat(board.avail(7, 2).units()).isEqualTo(6);
        assertThat(board.avail(5, 2).units()).isEqualTo(8);
    }

    @Test
    void aBoardSkipsTheHoursTheGridPreempts() {
        DayBoard board = DayBoard.build(grid, FRIDAY);
        assertThat(board.hours()).doesNotContain(19, 22).contains(23, 18);
    }

    @Test
    void askingABoardForABreakItDoesNotHave() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        assertThatThrownBy(() -> board.avail(7, 9))
                .isInstanceOf(InventoryError.class)
                .hasMessageContaining("no break 9 in hour 7");
    }

    @Test
    void aBoardCountsItsUnitsAndReportsHowSoldOutItIs() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        assertThat(board.totalUnits()).isEqualTo(23 * 24 + 30);
        assertThat(board.usedUnits()).isZero();
        assertThat(board.sellout()).isZero();
        board.avail(7, 2).add(aPlacement(1));
        board.avail(7, 3).add(aPlacement(2, SIXTY));
        assertThat(board.usedUnits()).isEqualTo(3);
        assertThat(board.freeUnits()).isEqualTo(board.totalUnits() - 3);
        assertThat(board.sellout()).isPositive().isLessThan(0.01);
    }

    @Test
    void anEmptyBoardIsNotSoldOutByDivision() {
        DayBoard empty = new DayBoard("KWLO", MONDAY);
        assertThat(empty.sellout()).isZero();
        assertThat(empty.totalUnits()).isZero();
    }

    @Test
    void theBoardListsTheAvailsThatWouldTakeAGivenLength() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        assertThat(board.openAvails(SIXTY)).hasSize(board.size());
        IntStream.rangeClosed(1, 3).forEach(line -> board.avail(7, 2).add(aPlacement(line, SIXTY)));
        assertThat(board.openAvails(THIRTY)).doesNotContain(board.avail(7, 2)).contains(board.avail(7, 3));
    }

    @Test
    void openAvailsComeBackInAirOrder() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        List<Avail> open = board.openAvails(THIRTY);
        for (int index = 1; index < open.size(); index++) {
            Avail earlier = open.get(index - 1);
            Avail later = open.get(index);
            assertThat(earlier.hour() < later.hour()
                            || (earlier.hour() == later.hour() && earlier.number() < later.number()))
                    .isTrue();
        }
        assertThat(open.get(0).hour()).isZero();
        assertThat(open.get(0).number()).isEqualTo(2);
    }

    @Test
    void theBoardFindsEverySpotOfOneLineWhereverItLanded() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        board.avail(7, 2).add(aPlacement(1));
        board.avail(9, 4).add(aPlacement(1));
        board.avail(9, 4).add(aPlacement(2));
        assertThat(board.countFor("25-1044", 1)).isEqualTo(2);
        assertThat(board.countFor("25-1044", 2)).isEqualTo(1);
        assertThat(board.countFor("25-1044", 9)).isZero();
        assertThat(board.find("25-1044", 1)).extracting(Avail::hour).containsExactly(7, 9);
    }

    @Test
    void everythingOnTheBoardComesBackInTheOrderItWillAir() {
        DayBoard board = DayBoard.build(grid, MONDAY);
        board.avail(9, 4).add(aPlacement(2));
        board.avail(7, 2).add(aPlacement(1));
        board.avail(7, 5).add(aPlacement(3));
        List<Timed> timed = board.placements();
        assertThat(timed).extracting(each -> each.at().hour()).containsExactly(7, 7, 9);
        assertThat(timed).extracting(each -> each.placement().line()).containsExactly(1, 3, 2);
    }
}
